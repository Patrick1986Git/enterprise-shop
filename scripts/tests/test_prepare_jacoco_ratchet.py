import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "prepare-jacoco-ratchet.py"
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


if __name__ == "__main__":
    unittest.main()
