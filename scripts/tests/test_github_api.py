import errno
import io
import json
import socket
import ssl
import unittest
from email.message import Message
from email.utils import formatdate
from http import HTTPStatus
from http.client import IncompleteRead
from queue import Empty
from unittest.mock import patch
from urllib.error import URLError
from urllib.response import addinfourl

from scripts import github_api as api


URL = "https://api.github.com/repos/example/project/rulesets"
TOKEN = "synthetic-test-token"


def response(code=200, *, data=b"[]", headers=None, url=URL, reason=None):
    message = Message()
    for key, value in (headers or {}).items():
        message[key] = value
    reply = addinfourl(io.BytesIO(data), message, url, code)
    reply.msg = reason or HTTPStatus(code).phrase
    return reply


def limited(code, message, headers=None):
    return response(code, data=json.dumps({"message": message}).encode(), headers=headers)


class GitHubAPITest(unittest.TestCase):
    def setUp(self):
        self.elapsed = 0
        self.pauses = []
        self.wall = 1_800_000_000
        time_patch = patch.multiple(api, monotonic=lambda: self.elapsed,
                                    time=lambda: self.wall + self.elapsed, sleep=self.advance)
        time_patch.start()
        self.addCleanup(time_patch.stop)
        # Mock both protocols so a redirect or a reused proxy request cannot use the network.
        http_patch = patch("urllib.request.HTTPHandler.http_open", side_effect=AssertionError("unexpected HTTP"))
        http_patch.start()
        self.addCleanup(http_patch.stop)
        proxy_patch = patch("urllib.request.getproxies", return_value={})
        proxy_patch.start()
        self.addCleanup(proxy_patch.stop)

    def advance(self, duration):
        self.pauses.append(duration)
        self.elapsed += duration

    def fetch(self, token=TOKEN, url=URL):
        return api.fetch_json(url, user_agent="repository-test", token=token)

    def test_authenticated_and_public_requests_use_same_fresh_api_path(self):
        with patch("urllib.request.HTTPSHandler.https_open", side_effect=[response(), response()]) as opened:
            self.assertEqual([], self.fetch())
            self.assertEqual([], self.fetch(token=None))
        authenticated, public = (call.args[0] for call in opened.call_args_list)
        self.assertEqual("Bearer " + TOKEN, authenticated.get_header("Authorization"))
        self.assertNotIn("Authorization", authenticated.headers)
        self.assertIsNone(public.get_header("Authorization"))
        for request in (authenticated, public):
            self.assertEqual(URL, request.full_url)
            self.assertEqual("no-cache", request.get_header("Cache-control"))
            self.assertEqual("2022-11-28", request.get_header("X-github-api-version"))
            self.assertEqual(15, request.timeout)

    def test_primary_limit_honors_later_of_reset_and_retry_after(self):
        primary = limited(403, "API rate limit exceeded for a test client.", {
            "X-RateLimit-Remaining": "0", "X-RateLimit-Reset": str(self.wall + 7), "Retry-After": "5",
        })
        with patch("urllib.request.HTTPSHandler.https_open", side_effect=[primary, response()]) as opened:
            self.assertEqual([], self.fetch())
        self.assertEqual(2, opened.call_count)
        self.assertEqual([7], self.pauses)

    def test_primary_limit_is_retried_only_once_after_reset(self):
        headers = {"X-RateLimit-Remaining": "0", "X-RateLimit-Reset": str(self.wall + 7)}
        replies = [response(403, headers=headers), response(429, headers=headers)]
        with patch("urllib.request.HTTPSHandler.https_open", side_effect=replies) as opened:
            with self.assertRaisesRegex(api.GitHubAPIError, "primary rate limit.*after retrying once"):
                self.fetch()
        self.assertEqual(2, opened.call_count)
        self.assertEqual([7], self.pauses)

    def test_primary_limit_with_absent_malformed_or_over_budget_reset_is_terminal(self):
        for reset, diagnostic in (("", "without usable reset"), ("tomorrow", "without usable reset"),
                                  (str(self.wall + 3600), "exceeds the acquisition budget")):
            with self.subTest(reset=reset):
                blocked = limited(403, "API rate limit exceeded", {
                    "X-RateLimit-Remaining": "0", "X-RateLimit-Reset": reset, "Retry-After": "3",
                })
                with patch("urllib.request.HTTPSHandler.https_open", return_value=blocked) as opened:
                    with self.assertRaisesRegex(api.GitHubAPIError, diagnostic):
                        self.fetch()
                opened.assert_called_once()
        self.assertEqual([], self.pauses)

    def test_secondary_limit_accepts_http_date_retry_after(self):
        blocked = limited(403, "You have exceeded a secondary rate limit.", {
            "Retry-After": formatdate(self.wall + 9, usegmt=True),
        })
        with patch("urllib.request.HTTPSHandler.https_open", side_effect=[blocked, response()]):
            self.assertEqual([], self.fetch())
        self.assertEqual([9], self.pauses)

    def test_headerless_or_malformed_rate_limit_waits_at_least_one_minute(self):
        for code, headers in ((403, {}), (429, {"Retry-After": "later"})):
            with self.subTest(code=code):
                blocked = limited(code, "You have exceeded a secondary rate limit.", headers)
                with patch("urllib.request.HTTPSHandler.https_open", side_effect=[blocked, response()]):
                    self.assertEqual([], self.fetch())
        self.assertEqual([60, 60], self.pauses)

    def test_second_headerless_throttle_exceeds_retry_budget(self):
        with patch("urllib.request.HTTPSHandler.https_open",
                   side_effect=[response(429), response(429)]) as opened:
            with self.assertRaisesRegex(api.GitHubAPIError, "rate limit.*exceeds the acquisition budget"):
                self.fetch()
        self.assertEqual(2, opened.call_count)
        self.assertEqual([60], self.pauses)

    def test_excessive_retry_after_never_causes_an_early_retry(self):
        for value in ("91", "9" * 5000, formatdate(self.wall + 3600, usegmt=True)):
            with patch("urllib.request.HTTPSHandler.https_open",
                       return_value=response(429, headers={"Retry-After": value})) as opened:
                with self.assertRaisesRegex(api.GitHubAPIError, "exceeds the acquisition budget"):
                    self.fetch()
            opened.assert_called_once()
        self.assertEqual([], self.pauses)

    def test_retries_429_and_selected_server_failures(self):
        cases = [(429, "3", 3), (500, "", 1), (502, "bad", 1), (503, "4", 4), (504, "-1", 1)]
        for code, value, delay in cases:
            with self.subTest(code=code):
                blocked = response(code, headers={"Retry-After": value})
                with patch("urllib.request.HTTPSHandler.https_open", side_effect=[blocked, response()]) as opened:
                    self.assertEqual([], self.fetch())
                self.assertEqual(2, opened.call_count)
                self.assertEqual(delay, self.pauses[-1])

    def test_retries_connection_failures_timeouts_temporary_dns_and_incomplete_read(self):
        failures = (URLError(ConnectionRefusedError()), TimeoutError(), OSError(errno.EHOSTUNREACH, "test"),
                    socket.gaierror(socket.EAI_AGAIN, "test"), IncompleteRead(b"short"))
        for failure in failures:
            with self.subTest(failure=type(failure).__name__):
                with patch("urllib.request.HTTPSHandler.https_open", side_effect=[failure, response()]) as opened:
                    self.assertEqual([], self.fetch())
                self.assertEqual(2, opened.call_count)
                self.assertEqual(1, self.pauses[-1])

    def test_retries_exhaust_after_three_attempts(self):
        errors = [URLError(ConnectionResetError()) for _ in range(3)]
        with patch("urllib.request.HTTPSHandler.https_open", side_effect=errors) as opened:
            with self.assertRaisesRegex(api.GitHubAPIError, "after 3 attempts"):
                self.fetch()
        self.assertEqual(3, opened.call_count)
        self.assertEqual([1, 2], self.pauses)

    def test_retries_use_fresh_https_requests_when_a_proxy_mutates_them(self):
        errors = [URLError(ConnectionResetError()) for _ in range(3)]
        with patch("urllib.request.getproxies", return_value={"https": "http://proxy.test:80"}), \
                patch("urllib.request.proxy_bypass", return_value=False), \
                patch("urllib.request.HTTPSHandler.https_open", side_effect=errors) as opened:
            with self.assertRaisesRegex(api.GitHubAPIError, "after 3 attempts"):
                self.fetch()
        requests = [call.args[0] for call in opened.call_args_list]
        self.assertEqual(3, len({id(request) for request in requests}))
        for request in requests:
            self.assertEqual("https", request.type)
            self.assertEqual("api.github.com", request._tunnel_host)

    def test_attempt_timeout_is_capped_by_remaining_deadline(self):
        def spend_time(_request):
            self.elapsed += 80
            return response(503)

        with patch("urllib.request.HTTPSHandler.https_open", side_effect=spend_time):
            with self.assertRaisesRegex(api.GitHubAPIError, "acquisition deadline"):
                self.fetch()
        self.assertEqual([1], self.pauses)

        timeouts = []

        def open_request(request):
            timeouts.append(request.timeout)
            if len(timeouts) == 1:
                return spend_time(request)
            return response()

        with patch("urllib.request.HTTPSHandler.https_open", side_effect=open_request):
            self.assertEqual([], self.fetch())
        self.assertEqual([15, 9], timeouts)

    def test_caller_has_a_hard_deadline_even_if_socket_does_not_return(self):
        with patch.object(api, "SimpleQueue") as make_queue, patch.object(api, "Thread") as worker:
            queue = make_queue.return_value
            queue.get.side_effect = Empty
            with self.assertRaisesRegex(api.GitHubAPIError, "acquisition deadline"):
                self.fetch()
        queue.get.assert_called_once_with(timeout=90)
        self.assertTrue(worker.call_args.kwargs["daemon"])
        worker.return_value.start.assert_called_once()

    def test_permanent_http_errors_are_terminal_and_sanitized(self):
        for code in (400, 401, 403, 404, 501, 505):
            with self.subTest(code=code):
                denial = limited(code, "Resource not accessible by integration " + TOKEN, {
                    "Retry-After": "2", "X-RateLimit-Remaining": "0",
                    "X-RateLimit-Reset": str(self.wall + 3),
                })
                denial.msg = TOKEN
                with patch("urllib.request.HTTPSHandler.https_open", return_value=denial) as opened:
                    with self.assertRaisesRegex(api.GitHubAPIError, f"HTTP {code}") as caught:
                        self.fetch()
                opened.assert_called_once()
                self.assertNotIn(TOKEN, str(caught.exception))
        self.assertEqual([], self.pauses)

    def test_untrusted_certificate_and_permanent_dns_failure_are_terminal(self):
        for cause in (ssl.SSLCertVerificationError(TOKEN), socket.gaierror(socket.EAI_NONAME, TOKEN)):
            with patch("urllib.request.HTTPSHandler.https_open", side_effect=URLError(cause)) as opened:
                with self.assertRaisesRegex(api.GitHubAPIError, "could not establish") as caught:
                    self.fetch()
            opened.assert_called_once()
            self.assertNotIn(TOKEN, str(caught.exception))
        self.assertEqual([], self.pauses)

    def test_rejects_unexpected_origins_before_opening_any_connection(self):
        with patch("urllib.request.HTTPSHandler.https_open") as https, \
                patch("urllib.request.HTTPHandler.http_open") as http:
            for url in ("http://api.github.com/repos/example/project", "https://github.com/download",
                        "https://api.github.com.example.test/repos", "https://api.github.com@evil.test",
                        "https://api.github.com:444/repos"):
                with self.subTest(url=url), self.assertRaisesRegex(api.GitHubAPIError, "unexpected.*origin"):
                    self.fetch(url=url)
        https.assert_not_called()
        http.assert_not_called()

    def test_redirects_are_terminal_and_do_not_send_a_second_request(self):
        for code, location in ((301, "/other"), (302, "https://release-assets.githubusercontent.com/" + TOKEN),
                               (307, "http://api.github.com/other"), (308, "https://example.test/" + TOKEN),
                               (304, "")):
            with self.subTest(code=code):
                headers = {"Location": location} if location else {}
                reply = response(code, headers=headers)
                with patch("urllib.request.HTTPSHandler.https_open", return_value=reply) as https, \
                        patch("urllib.request.HTTPHandler.http_open") as http:
                    with self.assertRaisesRegex(api.GitHubAPIError, "unexpected redirect") as caught:
                        self.fetch()
                https.assert_called_once()
                http.assert_not_called()
                self.assertNotIn(TOKEN, str(caught.exception))
        self.assertEqual([], self.pauses)

    def test_invalid_api_json_and_oversized_evidence_are_terminal(self):
        for body in (b"{", TOKEN.encode(), b'{"a":1,"a":2}', b"NaN", b" " * (api.MAX_JSON_BYTES + 1)):
            with patch("urllib.request.HTTPSHandler.https_open", return_value=response(data=body)) as opened:
                with self.assertRaisesRegex(api.GitHubAPIError, "invalid GitHub API JSON|oversized") as caught:
                    self.fetch()
            opened.assert_called_once()
            self.assertNotIn(TOKEN, str(caught.exception))
        self.assertEqual([], self.pauses)

    def test_invalid_header_credential_is_rejected_without_exposure(self):
        with patch("urllib.request.HTTPSHandler.https_open") as opened:
            with self.assertRaisesRegex(api.GitHubAPIError, "credential format") as caught:
                self.fetch(token=TOKEN + "\r\nInvalid: header")
        opened.assert_not_called()
        self.assertNotIn(TOKEN, str(caught.exception))
