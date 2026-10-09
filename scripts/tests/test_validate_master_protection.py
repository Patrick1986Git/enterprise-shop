import copy
import importlib.util
import itertools
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "validate-master-protection.py"
SPEC = importlib.util.spec_from_file_location("master_protection", SCRIPT)
POLICY = importlib.util.module_from_spec(SPEC)
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

    def test_temporary_transition_accepts_only_exact_seven_or_eight_contexts(self):
        contexts = sorted(EXPECTED_CHECKS)
        for flags in itertools.product((False, True), repeat=len(contexts)):
            observed = {context for context, present in zip(contexts, flags) if present}
            detail = valid_detail()
            status_parameters(detail)["required_status_checks"] = [
                {"context": context, "integration_id": 15368} for context in sorted(observed)
            ]
            with self.subTest(contexts=sorted(observed)):
                accepted = observed in (EXPECTED_CHECKS, EXPECTED_CHECKS - {"Analyze Python"})
                self.assertEqual(accepted, not POLICY.validate_ruleset_detail(detail))

    def test_python_integration_rename_and_duplicate_are_rejected(self):
        for mutation, message in (("integration", "integration_id"),
                                  ("rename", "required check contexts"),
                                  ("duplicate", "duplicate required check")):
            detail = valid_detail()
            checks = status_parameters(detail)["required_status_checks"]
            python = next(check for check in checks if check["context"] == "Analyze Python")
            if mutation == "integration":
                python["integration_id"] = 1
            elif mutation == "rename":
                python["context"] = "Analyze python"
            else:
                checks.append(copy.deepcopy(python))
            with self.subTest(mutation=mutation):
                self.assert_detail_invalid(detail, message)

    def test_all_required_contexts_reject_untrusted_integrations_in_both_transition_states(self):
        for include_python in (False, True):
            for context in EXPECTED_CHECKS - (set() if include_python else {"Analyze Python"}):
                detail = valid_detail()
                checks = status_parameters(detail)["required_status_checks"]
                checks[:] = [check for check in checks if include_python or check["context"] != "Analyze Python"]
                next(check for check in checks if check["context"] == context)["integration_id"] = None
                with self.subTest(include_python=include_python, context=context):
                    self.assert_detail_invalid(detail, "integration_id")

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


if __name__ == "__main__":
    unittest.main()
