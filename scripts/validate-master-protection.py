#!/usr/bin/env python3
"""Validate the observable live protected-master ruleset against repository policy."""

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

API_VERSION = "2022-11-28"
RULESET_ID = 20755388
RULESET_NAME = "Protect master"
TARGET_REF = "refs/heads/master"
GITHUB_ACTIONS_INTEGRATION_ID = 15368
REQUIRED_CHECKS = {
    "build",
    "docker-validation",
    "container-security",
    "Analyze Java/Kotlin",
    "dependency-review",
    "restore-pr-scope",
    "restore-rehearsal",
}
ALLOWED_MERGE_METHODS = {"merge", "squash", "rebase"}


def _object(value, location, errors):
    if not isinstance(value, dict):
        errors.append(f"{location} must be an object")
        return {}
    return value


def _exact(value, expected, location, errors):
    if value != expected:
        errors.append(f"{location} must be {expected!r}, got {value!r}")


def validate_ruleset_collection(payload):
    errors = []
    if not isinstance(payload, list):
        return ["ruleset collection must be an array"]
    active_repository_rulesets = [
        item for item in payload
        if isinstance(item, dict)
        and item.get("source_type") == "Repository"
        and item.get("enforcement") == "active"
    ]
    if len(active_repository_rulesets) != 1:
        errors.append(
            "expected exactly one active repository ruleset, got "
            f"{len(active_repository_rulesets)}"
        )
        return errors
    summary = active_repository_rulesets[0]
    _exact(summary.get("id"), RULESET_ID, "ruleset id", errors)
    _exact(summary.get("name"), RULESET_NAME, "ruleset name", errors)
    _exact(summary.get("target"), "branch", "ruleset target", errors)
    return errors


def validate_ruleset_detail(payload):
    errors = []
    ruleset = _object(payload, "ruleset detail", errors)
    if errors:
        return errors

    for key in ("id", "name", "target", "source_type", "enforcement", "conditions", "rules"):
        if key not in ruleset:
            errors.append(f"ruleset detail is missing {key!r}")
    if errors:
        return errors

    _exact(ruleset["id"], RULESET_ID, "ruleset id", errors)
    _exact(ruleset["name"], RULESET_NAME, "ruleset name", errors)
    _exact(ruleset["target"], "branch", "ruleset target", errors)
    _exact(ruleset["source_type"], "Repository", "ruleset source_type", errors)
    _exact(ruleset["enforcement"], "active", "ruleset enforcement", errors)

    conditions = _object(ruleset["conditions"], "conditions", errors)
    ref_name = _object(conditions.get("ref_name"), "conditions.ref_name", errors)
    _exact(ref_name.get("include"), [TARGET_REF], "target ref includes", errors)
    _exact(ref_name.get("exclude"), [], "target ref excludes", errors)

    rules = ruleset["rules"]
    if not isinstance(rules, list):
        errors.append("rules must be an array")
        return errors
    by_type = {}
    for index, rule in enumerate(rules):
        if not isinstance(rule, dict) or not isinstance(rule.get("type"), str):
            errors.append(f"rules[{index}] must be an object with a string type")
            continue
        by_type.setdefault(rule["type"], []).append(rule)

    for required_type in ("pull_request", "required_status_checks"):
        count = len(by_type.get(required_type, []))
        if count != 1:
            errors.append(f"expected exactly one {required_type!r} rule, got {count}")
    if errors:
        return errors

    pull_request = _object(
        by_type["pull_request"][0].get("parameters"),
        "pull_request.parameters",
        errors,
    )
    for key in ("required_approving_review_count", "required_review_thread_resolution", "allowed_merge_methods"):
        if key not in pull_request:
            errors.append(f"pull_request.parameters is missing {key!r}")
    _exact(pull_request.get("required_approving_review_count"), 0, "required approving reviews", errors)
    _exact(pull_request.get("required_review_thread_resolution"), True, "review-thread resolution", errors)
    methods = pull_request.get("allowed_merge_methods")
    if not isinstance(methods, list):
        errors.append("allowed merge methods must be an array")
    elif set(methods) != ALLOWED_MERGE_METHODS or len(methods) != len(ALLOWED_MERGE_METHODS):
        errors.append(f"allowed merge methods must be exactly {sorted(ALLOWED_MERGE_METHODS)!r}, got {methods!r}")

    status = _object(
        by_type["required_status_checks"][0].get("parameters"),
        "required_status_checks.parameters",
        errors,
    )
    for key in ("strict_required_status_checks_policy", "do_not_enforce_on_create", "required_status_checks"):
        if key not in status:
            errors.append(f"required_status_checks.parameters is missing {key!r}")
    _exact(status.get("strict_required_status_checks_policy"), True, "strict required checks", errors)
    _exact(status.get("do_not_enforce_on_create"), False, "do_not_enforce_on_create", errors)

    checks = status.get("required_status_checks")
    if not isinstance(checks, list):
        errors.append("required status checks must be an array")
    else:
        observed = {}
        for index, check in enumerate(checks):
            if not isinstance(check, dict) or not isinstance(check.get("context"), str):
                errors.append(f"required_status_checks[{index}] is malformed")
                continue
            context = check["context"]
            if context in observed:
                errors.append(f"duplicate required check context {context!r}")
            observed[context] = check.get("integration_id")
        if set(observed) != REQUIRED_CHECKS:
            errors.append(
                f"required check contexts must be exactly {sorted(REQUIRED_CHECKS)!r}, "
                f"got {sorted(observed)!r}"
            )
        for context, integration_id in observed.items():
            if integration_id != GITHUB_ACTIONS_INTEGRATION_ID:
                errors.append(
                    f"required check {context!r} integration_id must be "
                    f"{GITHUB_ACTIONS_INTEGRATION_ID}, got {integration_id!r}"
                )

    # GitHub omits bypass_actors from the safe public representation. Absence is
    # deliberately not interpreted as an empty set. If present, it is evidence.
    if "bypass_actors" in ruleset:
        bypass_actors = ruleset["bypass_actors"]
        if not isinstance(bypass_actors, list):
            errors.append("bypass_actors must be an array when present")
        elif bypass_actors:
            errors.append(f"bypass_actors must be empty, got {bypass_actors!r}")
    return errors


def fetch_json(url):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": API_VERSION,
            "User-Agent": "enterprise-shop-master-protection-validator",
        },
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        if response.status != 200:
            raise RuntimeError(f"GitHub API returned HTTP {response.status} for {url}")
        return json.load(response)


def load_json(path):
    with Path(path).open(encoding="utf-8") as handle:
        return json.load(handle)


def main():
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--repository", help="GitHub owner/repository to inspect without credentials")
    source.add_argument("--collection", type=Path, help="offline ruleset collection fixture")
    parser.add_argument("--detail", type=Path, help="offline ruleset detail fixture")
    args = parser.parse_args()

    try:
        if args.repository:
            base = f"https://api.github.com/repos/{args.repository}"
            collection = fetch_json(f"{base}/rulesets")
            detail = fetch_json(f"{base}/rulesets/{RULESET_ID}")
        else:
            if args.detail is None:
                parser.error("--detail is required with --collection")
            collection = load_json(args.collection)
            detail = load_json(args.detail)
        errors = validate_ruleset_collection(collection) + validate_ruleset_detail(detail)
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError, urllib.error.URLError) as error:
        print(f"Master protection evidence could not be obtained: {error}", file=sys.stderr)
        return 1

    if errors:
        print("Master protection policy validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("Observable protected-master ruleset policy is valid.")
    print("bypass_actors remains outside this safe API evidence unless explicitly present.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
