import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).parents[1] / "restore_pr_scope.py"
SPEC = importlib.util.spec_from_file_location("restore_pr_scope", SCRIPT)
SCOPE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCOPE)


class RestorePullRequestScopeTest(unittest.TestCase):
    def assert_runs(self, *paths):
        self.assertTrue(SCOPE.decide(list(paths))[0])

    def assert_skips(self, *paths):
        self.assertFalse(SCOPE.decide(list(paths))[0])

    def test_restore_script_change_runs(self):
        self.assert_runs("scripts/restore-rehearsal.sh")

    def test_historical_forward_fixture_change_runs(self):
        self.assert_runs("scripts/historical-forward-restore-fixture.sql")

    def test_flyway_migration_change_runs(self):
        self.assert_runs("src/main/resources/db/migration/V51__example.sql")

    def test_persistence_entity_change_runs(self):
        self.assert_runs("src/main/java/com/company/shop/module/order/entity/Order.java")

    def test_docker_and_build_inputs_run(self):
        for path in ("Dockerfile", "docker/postgres/Dockerfile", "pom.xml", ".mvn/wrapper/maven-wrapper.properties"):
            with self.subTest(path=path):
                self.assert_runs(path)

    def test_ci_restore_workflow_change_runs(self):
        self.assert_runs(".github/workflows/ci.yml")

    def test_documentation_only_change_skips(self):
        self.assert_skips("docs/operations/disaster-recovery.md")

    def test_unrelated_test_and_documentation_changes_skip(self):
        self.assert_skips("src/test/java/com/company/shop/ExampleTest.java", "docs/README.md")

    def test_mixed_change_runs(self):
        self.assert_runs("docs/README.md", "src/main/resources/application-prod.yml")

    def test_empty_or_malformed_evidence_runs(self):
        self.assertTrue(SCOPE.decide([])[0])
        self.assertTrue(SCOPE.decide([""])[0])
        self.assertTrue(SCOPE.decide(["../outside"])[0])

    def test_git_diff_failure_writes_fail_closed_decision(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            with mock.patch.object(
                SCOPE,
                "changed_paths",
                side_effect=subprocess.CalledProcessError(128, ["git", "diff"]),
            ), mock.patch(
                "sys.argv",
                ["restore_pr_scope.py", "--base-sha", "a", "--head-sha", "b", "--github-output", str(output)],
            ):
                self.assertEqual(0, SCOPE.main())
            self.assertEqual("run_restore=true\n", output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
