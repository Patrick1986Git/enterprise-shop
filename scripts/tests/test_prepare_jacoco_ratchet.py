import importlib.util
import hashlib
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "prepare-jacoco-ratchet.py"
WORKFLOW = Path(__file__).parents[2] / ".github/workflows/jacoco-ratchet.yml"
SPEC = importlib.util.spec_from_file_location("prepare_jacoco_ratchet", SCRIPT)
RATCHET = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RATCHET)
SHA = "0123456789abcdef0123456789abcdef01234567"


class JaCoCoRatchetTest(unittest.TestCase):
    def counter(self, covered, missed):
        return RATCHET.VALIDATOR.Counter(covered, missed)

    def decision(self, line, branch, baseline_line=(8, 2), baseline_branch=(7, 3)):
        return RATCHET.ratchet_decision(
            self.counter(*line), self.counter(*branch),
            {"LINE": self.counter(*baseline_line), "BRANCH": self.counter(*baseline_branch)},
        )[0]

    def provenance(self, pulls=None, sha=SHA):
        return {
            "pull_requests": pulls if pulls is not None else [
                {"number": 42, "merged_at": "2026-08-21T00:00:00Z", "merge_commit_sha": sha}
            ],
            "workflow": {
                "name": "CI", "run_number": 12, "run_id": 34, "head_sha": sha,
                "event": "push", "conclusion": "success",
            },
            "artifact": {"id": 56, "name": "jacoco-coverage-report", "digest": "sha256:" + "a" * 64},
        }

    def test_no_ratchet_when_exact_ratios_are_unchanged(self):
        self.assertFalse(self.decision((16, 4), (14, 6)))

    def test_ratchet_when_line_improves_and_branch_is_unchanged(self):
        self.assertTrue(self.decision((9, 1), (14, 6)))

    def test_ratchet_when_branch_improves_and_line_is_unchanged(self):
        self.assertTrue(self.decision((16, 4), (8, 2)))

    def test_ratchet_when_both_improve(self):
        self.assertTrue(self.decision((9, 1), (8, 2)))

    def test_rejects_line_regression(self):
        with self.assertRaisesRegex(RATCHET.ProposalError, "LINE"):
            self.decision((7, 3), (8, 2))

    def test_rejects_branch_regression(self):
        with self.assertRaisesRegex(RATCHET.ProposalError, "BRANCH"):
            self.decision((9, 1), (6, 4))

    def test_rejects_missing_provenance(self):
        with self.assertRaisesRegex(RATCHET.ProposalError, "exactly one"):
            RATCHET.validate_provenance(self.provenance(pulls=[]), SHA)

    def test_rejects_ambiguous_provenance(self):
        pull = {"number": 42, "merged_at": "now", "merge_commit_sha": SHA}
        with self.assertRaisesRegex(RATCHET.ProposalError, "exactly one"):
            RATCHET.validate_provenance(self.provenance(pulls=[pull, pull]), SHA)

    def test_rejects_stale_workflow_sha(self):
        provenance = self.provenance()
        provenance["workflow"]["head_sha"] = "f" * 40
        with self.assertRaisesRegex(RATCHET.ProposalError, "head SHA"):
            RATCHET.validate_provenance(provenance, SHA)

    def test_repeated_render_is_idempotent(self):
        workflow = self.provenance()["workflow"]
        first = RATCHET.render_policy(42, workflow, SHA, self.counter(9, 1), self.counter(8, 2))
        second = RATCHET.render_policy(42, workflow, SHA, self.counter(9, 1), self.counter(8, 2))
        self.assertEqual(first, second)

    def test_verifies_downloaded_artifact_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "artifact.zip"
            artifact.write_bytes(b"authoritative artifact bytes")
            expected = hashlib.sha256(artifact.read_bytes()).hexdigest()
            self.assertEqual(
                expected,
                RATCHET.verify_artifact_digest(artifact, f"sha256:{expected}"),
            )

    def test_rejects_downloaded_artifact_digest_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "artifact.zip"
            artifact.write_bytes(b"tampered bytes")
            with self.assertRaisesRegex(RATCHET.ProposalError, "mismatch"):
                RATCHET.verify_artifact_digest(artifact, "sha256:" + "0" * 64)

    def test_rejects_missing_or_noncanonical_artifact_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "artifact.zip"
            artifact.write_bytes(b"bytes")
            for digest in (None, "", "sha256:" + "A" * 64, "sha256:" + "0" * 63, "md5:" + "0" * 64):
                with self.subTest(digest=digest):
                    with self.assertRaisesRegex(RATCHET.ProposalError, "64 lowercase"):
                        RATCHET.verify_artifact_digest(artifact, digest)

    def test_prepare_rejects_malformed_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "baseline.json"
            path.write_text("{", encoding="utf-8")
            with self.assertRaises(RATCHET.VALIDATOR.PolicyValidationError):
                RATCHET.VALIDATOR.parse_policy(path)

    def test_prepare_rejects_malformed_report(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "jacoco.xml"
            path.write_text("<report>", encoding="utf-8")
            with self.assertRaises(RATCHET.VALIDATOR.ReportValidationError):
                RATCHET.VALIDATOR.parse_report(path)

    def test_prepare_rejects_wrong_report_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "jacoco.xml"
            path.write_text('<report name="Other"/>', encoding="utf-8")
            with self.assertRaisesRegex(RATCHET.VALIDATOR.ReportValidationError, "Enterprise Shop"):
                RATCHET.VALIDATOR.parse_report(path)


class JaCoCoRatchetWorkflowPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_uses_trusted_completed_ci_workflow_run_trigger(self):
        self.assertIn("workflow_run:", self.workflow)
        self.assertIn("workflows:\n      - CI", self.workflow)
        self.assertIn("types:\n      - completed", self.workflow)
        self.assertNotIn("pull_request_target", self.workflow)
        for condition in (
            "conclusion == 'success'", "event == 'push'", "head_branch == 'master'",
            "head_repository.full_name == github.repository",
        ):
            self.assertIn(condition, self.workflow)

    def test_scopes_write_permissions_to_proposal_job(self):
        workflow_default, proposal_job = self.workflow.split("jobs:", maxsplit=1)
        self.assertIn("permissions:\n  contents: read", workflow_default)
        self.assertNotIn(": write", workflow_default)
        self.assertIn("actions: write", proposal_job)
        self.assertIn("contents: write", proposal_job)
        self.assertIn("pull-requests: write", proposal_job)

    def test_uses_fixed_branch_without_master_push_or_merge(self):
        self.assertIn("BRANCH: automation/jacoco-baseline-ratchet", self.workflow)
        self.assertIn('"HEAD:refs/heads/${BRANCH}"', self.workflow)
        self.assertNotIn("HEAD:refs/heads/master", self.workflow)
        self.assertNotIn("gh pr merge", self.workflow)
        self.assertNotIn("auto-merge", self.workflow.lower())

    def test_revalidates_authoritative_run_metadata(self):
        for field in (
            '.name == "CI"', '.event == "push"', '.conclusion == "success"',
            '.head_branch == "master"', '.head_sha == $sha',
            '.head_repository.full_name == $repository',
        ):
            self.assertIn(field, self.workflow)

    def test_verifies_download_before_extraction(self):
        verification = self.workflow.index("--verify-artifact /tmp/jacoco.zip")
        extraction = self.workflow.index("unzip -q /tmp/jacoco.zip")
        self.assertLess(verification, extraction)

    def test_branch_lookup_distinguishes_absent_and_existing_branch(self):
        self.assertIn("git/matching-refs/heads/${BRANCH}", self.workflow)
        self.assertIn("if test \"$(jq 'length' <<<\"${matching_refs}\")\" = 0", self.workflow)
        self.assertIn('remote_sha=""', self.workflow)
        self.assertIn(".object.sha", self.workflow)

    def test_branch_lookup_fails_closed_on_api_error_or_ambiguous_response(self):
        lookup = self.workflow.index('matching_refs="$(gh api')
        push = self.workflow.index("push --force-with-lease", lookup)
        lookup_block = self.workflow[lookup:push]
        self.assertNotIn("|| true", lookup_block)
        self.assertIn("type == \"array\" and length <= 1", lookup_block)
        self.assertIn(".ref == $ref", lookup_block)

    def test_branch_lookup_accepts_only_commit_object_sha(self):
        self.assertIn('.object.type == "commit"', self.workflow)
        self.assertIn('test("^[0-9a-f]{40}$")', self.workflow)

    def test_push_uses_observed_sha_or_absence_as_exact_lease(self):
        self.assertIn(
            'push --force-with-lease="refs/heads/${BRANCH}:${remote_sha}"',
            self.workflow,
        )


if __name__ == "__main__":
    unittest.main()
