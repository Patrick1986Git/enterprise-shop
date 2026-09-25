import copy
import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "validate-master-protection.py"
SPEC = importlib.util.spec_from_file_location("master_protection", SCRIPT)
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)


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
                        for context in sorted(POLICY.REQUIRED_CHECKS)
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
