import contextlib
import copy
import importlib.util
import io
import itertools
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_github_api import TOKEN, limited, response


SCRIPT = Path(__file__).parents[1] / "validate-master-protection.py"
SPEC = importlib.util.spec_from_file_location("master_protection", SCRIPT)
POLICY = importlib.util.module_from_spec(SPEC)
with patch.object(sys, "path", [str(SCRIPT.parent), *sys.path]):
    SPEC.loader.exec_module(POLICY)

EXPECTED_CHECKS = {
    "build", "docker-validation", "container-security", "Analyze Java/Kotlin",
    "Analyze Python", "dependency-review", "restore-pr-scope", "restore-rehearsal",
}


def valid_collection():
    return [{
        "id": POLICY.RULESET_ID,
        "name": POLICY.RULESET_NAME,
        "target": "branch",
        "source_type": "Repository",
        "enforcement": "active",
    }]


def valid_detail():
    return {
        "id": POLICY.RULESET_ID,
        "name": POLICY.RULESET_NAME,
        "target": "branch",
        "source_type": "Repository",
        "enforcement": "active",
        "conditions": {"ref_name": {"include": [POLICY.TARGET_REF], "exclude": []}},
        "rules": [
            {
                "type": "pull_request",
                "parameters": {
                    "required_approving_review_count": 0,
                    "required_review_thread_resolution": True,
                    "allowed_merge_methods": ["merge", "squash", "rebase"],
                },
            },
            {
                "type": "required_status_checks",
                "parameters": {
                    "strict_required_status_checks_policy": True,
                    "do_not_enforce_on_create": False,
                    "required_status_checks": [
                        {"context": context, "integration_id": POLICY.GITHUB_ACTIONS_INTEGRATION_ID}
                        for context in sorted(EXPECTED_CHECKS)
                    ],
                },
            },
        ],
    }


def status_parameters(detail):
    return next(rule for rule in detail["rules"] if rule["type"] == "required_status_checks")["parameters"]


def pull_request_parameters(detail):
    return next(rule for rule in detail["rules"] if rule["type"] == "pull_request")["parameters"]


class MasterProtectionPolicyTest(unittest.TestCase):
    def assert_detail_invalid(self, detail, message):
        errors = POLICY.validate_ruleset_detail(detail)
        self.assertTrue(errors)
        self.assertTrue(any(message in error for error in errors), errors)

    def test_accepts_exact_valid_ruleset_with_unobservable_bypass_actors(self):
        self.assertEqual([], POLICY.validate_ruleset_collection(valid_collection()))
        self.assertEqual([], POLICY.validate_ruleset_detail(valid_detail()))

    def test_intended_contract_requires_both_codeql_jobs_and_existing_seven(self):
        self.assertEqual(EXPECTED_CHECKS, POLICY.REQUIRED_CHECKS)
        self.assertEqual(8, len(POLICY.REQUIRED_CHECKS))

    def test_final_contract_accepts_only_exact_eight_contexts(self):
        contexts = sorted(EXPECTED_CHECKS)
        for flags in itertools.product((False, True), repeat=len(contexts)):
            observed = {context for context, present in zip(contexts, flags) if present}
            detail = valid_detail()
            status_parameters(detail)["required_status_checks"] = [
                {"context": context, "integration_id": 15368} for context in sorted(observed)
            ]
            with self.subTest(contexts=sorted(observed)):
                accepted = observed == EXPECTED_CHECKS
                self.assertEqual(accepted, not POLICY.validate_ruleset_detail(detail))

    def test_all_required_contexts_reject_integration_rename_and_duplicate(self):
        for context in sorted(EXPECTED_CHECKS):
            for mutation, message in (("integration", "integration_id"),
                                      ("rename", "required check contexts"),
                                      ("duplicate", "duplicate required check")):
                detail = valid_detail()
                checks = status_parameters(detail)["required_status_checks"]
                check = next(item for item in checks if item["context"] == context)
                if mutation == "integration":
                    check["integration_id"] = 1
                elif mutation == "rename":
                    check["context"] = context.upper()
                else:
                    checks.append(copy.deepcopy(check))
                with self.subTest(context=context, mutation=mutation):
                    self.assert_detail_invalid(detail, message)

    def test_all_required_contexts_reject_untrusted_integrations(self):
        for context in sorted(EXPECTED_CHECKS):
            detail = valid_detail()
            checks = status_parameters(detail)["required_status_checks"]
            next(check for check in checks if check["context"] == context)["integration_id"] = None
            with self.subTest(context=context):
                self.assert_detail_invalid(detail, "integration_id")

    def test_rejects_previous_seven_checks_without_python(self):
        self.assert_missing_check("Analyze Python")

    def test_rejects_missing_dependency_review(self):
        self.assert_missing_check("dependency-review")

    def test_rejects_missing_restore_pr_scope(self):
        self.assert_missing_check("restore-pr-scope")

    def test_rejects_missing_restore_rehearsal(self):
        self.assert_missing_check("restore-rehearsal")

    def test_rejects_missing_build(self):
        self.assert_missing_check("build")

    def assert_missing_check(self, context):
        detail = valid_detail()
        checks = status_parameters(detail)["required_status_checks"]
        status_parameters(detail)["required_status_checks"] = [
            check for check in checks if check["context"] != context
        ]
        self.assert_detail_invalid(detail, "required check contexts must be exactly")

    def test_rejects_additional_required_check_as_unexpected_drift(self):
        detail = valid_detail()
        status_parameters(detail)["required_status_checks"].append(
            {"context": "unexpected", "integration_id": POLICY.GITHUB_ACTIONS_INTEGRATION_ID}
        )
        self.assert_detail_invalid(detail, "required check contexts must be exactly")

    def test_rejects_disabled_strict_mode(self):
        detail = valid_detail()
        status_parameters(detail)["strict_required_status_checks_policy"] = False
        self.assert_detail_invalid(detail, "strict required checks")

    def test_rejects_wrong_branch_target(self):
        detail = valid_detail()
        detail["conditions"]["ref_name"]["include"] = ["refs/heads/develop"]
        self.assert_detail_invalid(detail, "target ref includes")

    def test_rejects_inactive_ruleset(self):
        detail = valid_detail()
        detail["enforcement"] = "disabled"
        self.assert_detail_invalid(detail, "ruleset enforcement")

    def test_rejects_wrong_integration_id(self):
        detail = valid_detail()
        status_parameters(detail)["required_status_checks"][0]["integration_id"] = 1
        self.assert_detail_invalid(detail, "integration_id")

    def test_rejects_altered_review_thread_requirement(self):
        detail = valid_detail()
        pull_request_parameters(detail)["required_review_thread_resolution"] = False
        self.assert_detail_invalid(detail, "review-thread resolution")

    def test_rejects_malformed_or_incomplete_response(self):
        self.assert_detail_invalid({"id": POLICY.RULESET_ID}, "missing")
        self.assertTrue(POLICY.validate_ruleset_detail([]))
        detail = valid_detail()
        del status_parameters(detail)["required_status_checks"]
        self.assert_detail_invalid(detail, "missing 'required_status_checks'")

    def test_distinguishes_missing_bypass_evidence_from_empty_value(self):
        detail = valid_detail()
        self.assertEqual([], POLICY.validate_ruleset_detail(detail))
        detail["bypass_actors"] = []
        self.assertEqual([], POLICY.validate_ruleset_detail(detail))
        detail["bypass_actors"] = [{"actor_id": 1}]
        self.assert_detail_invalid(detail, "bypass_actors must be empty")
        detail["bypass_actors"] = None
        self.assert_detail_invalid(detail, "bypass_actors must be an array")

    def test_rejects_branch_creation_exemption(self):
        detail = valid_detail()
        status_parameters(detail)["do_not_enforce_on_create"] = True
        self.assert_detail_invalid(detail, "do_not_enforce_on_create")

    def test_rejects_changed_allowed_merge_methods(self):
        detail = valid_detail()
        pull_request_parameters(detail)["allowed_merge_methods"] = ["squash"]
        self.assert_detail_invalid(detail, "allowed merge methods")

    def test_rejects_multiple_active_repository_rulesets(self):
        collection = valid_collection() + [copy.deepcopy(valid_collection()[0])]
        self.assertTrue(POLICY.validate_ruleset_collection(collection))


class MasterProtectionAcquisitionTest(unittest.TestCase):
    REPOSITORY = "example/project"
    COLLECTION = f"https://api.github.com/repos/{REPOSITORY}/rulesets"
    DETAIL = f"{COLLECTION}/{POLICY.RULESET_ID}"

    def setUp(self):
        self.output = io.StringIO()
        self.errors = io.StringIO()
        proxy_patch = patch("urllib.request.getproxies", return_value={})
        proxy_patch.start()
        self.addCleanup(proxy_patch.stop)

    def invoke(self, *arguments):
        with patch.object(sys, "argv", [str(SCRIPT), *arguments]), contextlib.redirect_stdout(self.output), \
                contextlib.redirect_stderr(self.errors):
            return POLICY.main()

    def live(self, replies, token=TOKEN):
        with patch.dict(os.environ, {"GH_TOKEN": token}), \
                patch("urllib.request.HTTPSHandler.https_open", side_effect=replies) as opened:
            code = self.invoke("--repository", self.REPOSITORY)
        return code, [call.args[0] for call in opened.call_args_list]

    def evidence(self, collection, detail):
        return [response(data=json.dumps(collection).encode(), url=self.COLLECTION),
                response(data=json.dumps(detail).encode(), url=self.DETAIL)]

    def test_authenticated_live_evidence_recovers_from_rate_limit(self):
        detail = valid_detail()
        detail["bypass_actors"] = []
        rate_limit = limited(403, "API rate limit exceeded", {
            "X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "1800000005",
        })
        with patch("github_api.time", return_value=1_800_000_000), patch("github_api.sleep") as sleep:
            code, requests = self.live([rate_limit, *self.evidence(valid_collection(), detail)])
        self.assertEqual(0, code)
        self.assertEqual([self.COLLECTION, self.COLLECTION, self.DETAIL], [req.full_url for req in requests])
        self.assertTrue(all(req.get_header("Authorization") == "Bearer " + TOKEN for req in requests))
        sleep.assert_called_once_with(5)
        self.assertIn("observed bypass_actors list is empty", self.output.getvalue())

    def test_public_and_read_only_response_with_omitted_bypass_reports_limit(self):
        for token in ("", TOKEN):
            with self.subTest(authenticated=bool(token)):
                code, requests = self.live(self.evidence(valid_collection(), valid_detail()), token=token)
                self.assertEqual(0, code)
                header = "Bearer " + TOKEN if token else None
                self.assertEqual([header, header], [request.get_header("Authorization") for request in requests])
                self.assertIn("remains unverified", self.output.getvalue())
                self.assertNotIn("list is empty", self.output.getvalue())

    def test_offline_fixtures_never_contact_github(self):
        with tempfile.TemporaryDirectory() as temporary:
            collection = Path(temporary) / "collection.json"
            detail = Path(temporary) / "detail.json"
            collection.write_text(json.dumps(valid_collection()))
            detail.write_text(json.dumps(valid_detail()))
            with patch.dict(os.environ, {"GH_TOKEN": TOKEN}), patch("github_api.build_opener") as opener:
                self.assertEqual(0, self.invoke("--collection", str(collection), "--detail", str(detail)))
        opener.assert_not_called()

    def test_live_schema_and_policy_violations_are_terminal(self):
        wrong_id = valid_detail()
        wrong_id["id"] += 1
        weakened = valid_detail()
        status_parameters(weakened)["strict_required_status_checks_policy"] = 1
        bypass = valid_detail()
        bypass["bypass_actors"] = [{"actor_id": TOKEN}]
        cases = (([None], valid_detail(), "collection entries"), (valid_collection(), [], "detail must be an object"),
                 (valid_collection(), wrong_id, "ruleset id"), (valid_collection(), weakened, "strict required checks"),
                 (valid_collection(), bypass, "bypass_actors must be empty"))
        for collection, detail, diagnostic in cases:
            with self.subTest(diagnostic=diagnostic), patch("github_api.sleep") as sleep:
                code, requests = self.live(self.evidence(collection, detail))
                self.assertEqual(1, code)
                self.assertEqual(2, len(requests))
                self.assertIn(diagnostic, self.errors.getvalue())
                self.assertNotIn(TOKEN, self.errors.getvalue())
                sleep.assert_not_called()

    def test_failed_or_invalid_fresh_detail_blocks_policy_validation(self):
        for failures, diagnostic, delays in (
            ([response(503), response(503), response(503)], "after 3 attempts", [1, 2]),
            ([response(data=b"{", url=self.DETAIL)], "invalid GitHub API JSON", []),
            ([limited(403, "Resource not accessible by integration " + TOKEN)], "HTTP 403", []),
        ):
            with self.subTest(diagnostic=diagnostic), patch("github_api.sleep") as sleep:
                collection = response(data=json.dumps(valid_collection()).encode(), url=self.COLLECTION)
                code, requests = self.live([collection, *failures])
                self.assertEqual(1, code)
                self.assertEqual(1 + len(failures), len(requests))
                self.assertTrue(all(req.get_header("Authorization") == "Bearer " + TOKEN for req in requests))
                self.assertIn(diagnostic, self.errors.getvalue())
                self.assertNotIn(TOKEN, self.errors.getvalue())
                self.assertEqual(delays, [call.args[0] for call in sleep.call_args_list])
        self.assertEqual("", self.output.getvalue())


if __name__ == "__main__":
    unittest.main()
