import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "validate-github-actions-policy.py"
SPEC = importlib.util.spec_from_file_location("github_actions_policy", SCRIPT)
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
SHA = "0123456789abcdef0123456789abcdef01234567"


class GitHubActionsPolicyTest(unittest.TestCase):
    def validate(self, contents, filename="fixture.yml"):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / filename
            path.write_text(contents, encoding="utf-8")
            return POLICY.validate_workflows(Path(directory))

    def workflow(self, reference, extra=""):
        return f"jobs:\n  test:\n    steps:\n      - uses: {reference}\n{extra}"

    def test_accepts_external_action_pinned_to_full_sha(self):
        self.assertEqual([], self.validate(self.workflow(f"owner/action@{SHA}")))

    def test_rejects_mutable_major_tag(self):
        self.assertTrue(self.validate(self.workflow("owner/action@v4")))

    def test_rejects_release_tag(self):
        self.assertTrue(self.validate(self.workflow("owner/action@v4.2.2")))

    def test_rejects_branch(self):
        self.assertTrue(self.validate(self.workflow("owner/action@main")))

    def test_rejects_short_sha(self):
        self.assertTrue(self.validate(self.workflow("owner/action@0123456")))

    def test_allows_local_action(self):
        self.assertEqual([], self.validate(self.workflow("./.github/actions/example")))

    def test_rejects_checkout_without_disabled_credentials(self):
        violations = self.validate(self.workflow(f"actions/checkout@{SHA}"))
        self.assertIn("persist-credentials: false", violations[0])

    def test_accepts_checkout_with_disabled_credentials(self):
        workflow = self.workflow(
            f"actions/checkout@{SHA}",
            "        with:\n          persist-credentials: false\n",
        )
        self.assertEqual([], self.validate(workflow))

    def test_reports_workflow_filename_and_offending_action(self):
        violations = self.validate(self.workflow("owner/action@main"), "named.yaml")
        self.assertIn("named.yaml", violations[0])
        self.assertIn("owner/action@main", violations[0])

    def test_scans_yml_and_yaml_files(self):
        with tempfile.TemporaryDirectory() as directory:
            workflows = Path(directory)
            (workflows / "one.yml").write_text(
                self.workflow("owner/one@main"), encoding="utf-8"
            )
            (workflows / "two.yaml").write_text(
                self.workflow("owner/two@main"), encoding="utf-8"
            )
            violations = POLICY.validate_workflows(workflows)
        self.assertEqual(2, len(violations))
        self.assertTrue(any("one.yml" in violation for violation in violations))
        self.assertTrue(any("two.yaml" in violation for violation in violations))

    def test_ignores_action_like_text_in_run_block(self):
        workflow = "jobs:\n  test:\n    steps:\n      - run: |\n          uses: image@example:tag\n"
        self.assertEqual([], self.validate(workflow))


if __name__ == "__main__":
    unittest.main()
