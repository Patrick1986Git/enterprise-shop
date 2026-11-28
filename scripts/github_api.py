"""Read fresh GitHub REST evidence with bounded, origin-scoped retries."""

import errno
import http.client
import json
import re
import socket
from datetime import timezone
from email.utils import parsedate_to_datetime
from queue import Empty, SimpleQueue
from threading import Thread
from time import monotonic, sleep, time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

API_VERSION = "2022-11-28"
MAX_ATTEMPTS = 3
TOTAL_SECONDS = 90
SOCKET_TIMEOUT = 15
MAX_JSON_BYTES = 1024 * 1024
TRANSIENT_STATUS = {500, 502, 503, 504}
TRANSIENT_ERRNO = {errno.EHOSTUNREACH, errno.ENETDOWN, errno.ENETUNREACH, errno.ETIMEDOUT}


class GitHubAPIError(RuntimeError):
    """An acquisition failure safe to report without the request or response."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _decimal(value):
    value = value.strip()
    if re.fullmatch(r"[0-9]+", value):
        digits = value.lstrip("0")
        return float("inf") if len(digits) > 18 else int(digits or "0")
    return None


def _retry_after(value, now):
    seconds = _decimal(value)
    if seconds is not None:
        return seconds
    try:
        date = parsedate_to_datetime(value)
        if date.tzinfo is None:
            date = date.replace(tzinfo=timezone.utc)
        return max(0, date.timestamp() - now)
    except (ValueError, OverflowError, OSError):
        return None


def _rate_limit_message(error):
    try:
        payload = json.loads(error.read(8192))
        if isinstance(payload, dict) and isinstance(payload.get("message"), str):
            return payload["message"].lower()
    except (ValueError, RecursionError, OSError, http.client.HTTPException):
        pass
    return str(error.reason).lower()


def _http_retry(error, attempt):
    """Return a safe reason, a minimum delay (None if terminal), and primary-limit state."""
    code = error.code
    reason = f"returned HTTP {code}"
    if 300 <= code < 400:
        return f"returned an unexpected redirect (HTTP {code})", None, False
    if code not in TRANSIENT_STATUS and code not in {403, 429}:
        return reason, None, False

    remaining = error.headers.get("X-RateLimit-Remaining", "").strip()
    primary = remaining == "0"
    rate_limited = code == 429
    if code == 403:
        message = _rate_limit_message(error)
        if message.startswith(("api rate limit exceeded", "rate limit exceeded")):
            primary = True
        elif not (message.startswith(("you have exceeded a secondary rate limit", "secondary rate limit"))
                  or (primary and message in {"", "forbidden"})):
            return reason, None, False
        rate_limited = True

    now = time()
    retry_after = _retry_after(error.headers.get("Retry-After", ""), now)
    reset = _decimal(error.headers.get("X-RateLimit-Reset", "")) if primary else None
    delays = [2 ** attempt]
    if retry_after is not None:
        delays.append(retry_after)
    if reset is not None:
        delays.append(reset - now)
    if primary:
        reason = f"hit the primary rate limit (HTTP {code})"
        if reset is None:
            return f"{reason} without usable reset evidence", None, True
    elif rate_limited:
        reason = f"hit a rate limit (HTTP {code})"
        if retry_after is None:
            delays.append(60 * 2 ** attempt)
    return reason, max(delays), primary


def _transient_connection(error):
    cause = error.reason if isinstance(error, URLError) else error
    if isinstance(cause, socket.gaierror):
        return cause.errno == socket.EAI_AGAIN
    return isinstance(cause, (ConnectionError, TimeoutError, http.client.IncompleteRead)) or (
        isinstance(cause, OSError) and cause.errno in TRANSIENT_ERRNO
    )


def _unique_object(pairs):
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError("duplicate JSON key")
    return result


def _invalid_constant(_value):
    raise ValueError("invalid JSON constant")


def _fetch(url, headers, token, deadline):
    opener = build_opener(NoRedirect())
    primary_retried = False
    for attempt in range(MAX_ATTEMPTS):
        remaining = deadline - monotonic()
        if remaining <= 0:
            raise GitHubAPIError("exceeded the GitHub API acquisition deadline")
        # urllib's proxy handling mutates Request objects.
        request = Request(url, headers=headers)
        if token:
            request.add_unredirected_header("Authorization", f"Bearer {token}")
        try:
            with opener.open(request, timeout=min(SOCKET_TIMEOUT, remaining)) as response:
                if response.geturl() != request.full_url or 300 <= response.status < 400:
                    raise GitHubAPIError("received an unexpected GitHub API redirect")
                if response.status != 200:
                    raise GitHubAPIError(f"returned HTTP {response.status}")
                data = response.read(MAX_JSON_BYTES + 1)
        except HTTPError as error:
            with error:
                reason, delay, primary = _http_retry(error, attempt)
        except (URLError, OSError, http.client.HTTPException) as error:
            if not _transient_connection(error):
                raise GitHubAPIError("could not establish or read the GitHub API connection") from None
            reason, delay, primary = "had a GitHub API connection failure or timeout", 2 ** attempt, False
        else:
            if len(data) > MAX_JSON_BYTES:
                raise GitHubAPIError("returned oversized GitHub API evidence")
            try:
                return json.loads(data, object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
            except (ValueError, RecursionError):
                raise GitHubAPIError("returned invalid GitHub API JSON") from None

        if delay is None:
            raise GitHubAPIError(reason)
        if primary and primary_retried:
            raise GitHubAPIError(f"{reason} after retrying once")
        if attempt + 1 == MAX_ATTEMPTS:
            raise GitHubAPIError(f"{reason} after {MAX_ATTEMPTS} attempts")
        if delay >= deadline - monotonic():
            raise GitHubAPIError(f"{reason}; retry delay exceeds the acquisition budget")
        primary_retried |= primary
        sleep(delay)


def fetch_json(url, *, user_agent, token=None):
    try:
        target = urlsplit(url)
    except ValueError:
        raise GitHubAPIError("invalid GitHub API URL") from None
    if target.scheme != "https" or target.netloc != "api.github.com" or target.fragment:
        raise GitHubAPIError("unexpected GitHub API origin or fragment")
    if token and not re.fullmatch(r"[-A-Za-z0-9._~+/]+=*", token):
        raise GitHubAPIError("invalid GitHub API credential format")
    headers = {
        "Accept": "application/vnd.github+json",
        "Cache-Control": "no-cache",
        "X-GitHub-Api-Version": API_VERSION,
        "User-Agent": user_agent,
    }

    deadline = monotonic() + TOTAL_SECONDS
    result = SimpleQueue()

    def acquire():
        try:
            result.put((True, _fetch(url, headers, token, deadline)))
        except GitHubAPIError as error:
            result.put((False, str(error)))
        except Exception:
            result.put((False, "unexpected GitHub API acquisition failure"))

    # Socket timeouts do not bound DNS or a response that keeps sending bytes.
    # A stalled worker cannot start another attempt after the overall deadline.
    Thread(target=acquire, daemon=True).start()
    try:
        acquired, value = result.get(timeout=max(0, deadline - monotonic()))
    except Empty:
        raise GitHubAPIError("exceeded the GitHub API acquisition deadline") from None
    if monotonic() >= deadline:
        raise GitHubAPIError("exceeded the GitHub API acquisition deadline")
    if not acquired:
        raise GitHubAPIError(value)
    return value
