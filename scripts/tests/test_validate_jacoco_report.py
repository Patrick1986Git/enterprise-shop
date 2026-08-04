import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "validate-jacoco-report.py"
SPEC = importlib.util.spec_from_file_location("validate_jacoco_report", SCRIPT)
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


class JaCoCoReportTest(unittest.TestCase):
    def report(self, counters, package_counters='<counter type="LINE" missed="1" covered="1"/>'):
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<report name="Enterprise Shop">
  <package name="com/company/shop">
    <class name="com/company/shop/Example">{package_counters}</class>
    {package_counters}
  </package>
  {counters}
</report>'''

    def parse(self, contents):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "jacoco.xml"
            path.write_text(contents, encoding="utf-8")
            return REPORT.parse_report(path)

    def policy(self, lines=(8, 2), branches=(7, 3)):
        return {
            "schema_version": 1,
            "metrics": {
                "LINE": {"covered": lines[0], "missed": lines[1]},
                "BRANCH": {"covered": branches[0], "missed": branches[1]},
            },
        }

    def parse_policy(self, policy):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "policy.json"
            if isinstance(policy, str):
                path.write_text(policy, encoding="utf-8")
            else:
                path.write_text(json.dumps(policy), encoding="utf-8")
            return REPORT.parse_policy(path)

    def run_main(self, counters, policy=None, companions=True):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "jacoco.xml"
            report.write_text(self.report(counters), encoding="utf-8")
            if companions:
                (report.parent / "index.html").write_text("html", encoding="utf-8")
                (report.parent / "jacoco.csv").write_text("csv", encoding="utf-8")
            arguments = [str(report)]
            if policy is not None:
                policy_path = report.parent / "policy.json"
                policy_path.write_text(json.dumps(policy), encoding="utf-8")
                arguments.extend(("--policy", str(policy_path)))
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                with unittest.mock.patch.object(sys, "argv", [str(SCRIPT), *arguments]):
                    result = REPORT.main()
            return result, stdout.getvalue(), stderr.getvalue()

    def test_parses_valid_report(self):
        lines, branches = self.parse(
            self.report('<counter type="LINE" missed="2" covered="8"/>')
        )
        self.assertEqual(REPORT.Counter(covered=8, missed=2), lines)
        self.assertEqual(REPORT.Counter(covered=0, missed=0), branches)

    def test_rejects_missing_report_file(self):
        with self.assertRaisesRegex(REPORT.ReportValidationError, "does not exist"):
            REPORT.parse_report(Path("missing-jacoco.xml"))

    def test_rejects_malformed_xml(self):
        with self.assertRaisesRegex(REPORT.ReportValidationError, "malformed XML"):
            self.parse("<report>")

    def test_rejects_absent_line_counter(self):
        with self.assertRaisesRegex(REPORT.ReportValidationError, "LINE counter"):
            self.parse(self.report('<counter type="INSTRUCTION" missed="2" covered="8"/>'))

    def test_handles_zero_total_lines(self):
        lines, _ = self.parse(
            self.report('<counter type="LINE" missed="0" covered="0"/>')
        )
        self.assertEqual("0.00%", REPORT.percentage(lines))

    def test_parses_valid_branch_counter(self):
        _, branches = self.parse(
            self.report(
                '<counter type="LINE" missed="2" covered="8"/>'
                '<counter type="BRANCH" missed="3" covered="7"/>'
            )
        )
        self.assertEqual(REPORT.Counter(covered=7, missed=3), branches)

    def test_handles_zero_total_branches(self):
        _, branches = self.parse(
            self.report(
                '<counter type="LINE" missed="2" covered="8"/>'
                '<counter type="BRANCH" missed="0" covered="0"/>'
            )
        )
        self.assertEqual("0.00%", REPORT.percentage(branches))

    def test_rejects_negative_counter_value(self):
        with self.assertRaisesRegex(REPORT.ReportValidationError, "non-negative"):
            self.parse(self.report('<counter type="LINE" missed="-1" covered="1"/>'))

    def test_rejects_non_integer_counter_value(self):
        with self.assertRaisesRegex(REPORT.ReportValidationError, "integer"):
            self.parse(self.report('<counter type="LINE" missed="one" covered="1"/>'))

    def test_formats_percentage_deterministically(self):
        self.assertEqual(
            "66.67%", REPORT.percentage(REPORT.Counter(covered=2, missed=1))
        )

    def test_formats_percentage_with_round_half_up(self):
        self.assertEqual(
            "12.35%", REPORT.percentage(REPORT.Counter(covered=12345, missed=87655))
        )

    def test_rejects_missing_index_html(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "jacoco.csv").write_text("csv", encoding="utf-8")
            with self.assertRaisesRegex(REPORT.ReportValidationError, "index.html"):
                REPORT.validate_companion_reports(Path(directory))

    def test_rejects_empty_index_html(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "index.html").touch()
            (Path(directory) / "jacoco.csv").write_text("csv", encoding="utf-8")
            with self.assertRaisesRegex(REPORT.ReportValidationError, "index.html"):
                REPORT.validate_companion_reports(Path(directory))

    def test_rejects_missing_jacoco_csv(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "index.html").write_text("html", encoding="utf-8")
            with self.assertRaisesRegex(REPORT.ReportValidationError, "jacoco.csv"):
                REPORT.validate_companion_reports(Path(directory))

    def test_rejects_empty_jacoco_csv(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "index.html").write_text("html", encoding="utf-8")
            (Path(directory) / "jacoco.csv").touch()
            with self.assertRaisesRegex(REPORT.ReportValidationError, "jacoco.csv"):
                REPORT.validate_companion_reports(Path(directory))

    def test_rejects_missing_policy_file(self):
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "does not exist"):
            REPORT.parse_policy(Path("missing-policy.json"))

    def test_rejects_malformed_policy_json(self):
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "malformed JSON"):
            self.parse_policy("{")

    def test_rejects_unsupported_policy_schema(self):
        policy = self.policy()
        policy["schema_version"] = 2
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "schema_version"):
            self.parse_policy(policy)

    def test_rejects_missing_line_policy_metric(self):
        policy = self.policy()
        del policy["metrics"]["LINE"]
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "LINE metric"):
            self.parse_policy(policy)

    def test_rejects_missing_branch_policy_metric(self):
        policy = self.policy()
        del policy["metrics"]["BRANCH"]
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "BRANCH metric"):
            self.parse_policy(policy)

    def test_rejects_negative_policy_value(self):
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "non-negative"):
            self.parse_policy(self.policy(lines=(-1, 2)))

    def test_rejects_non_integer_policy_value(self):
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "integer"):
            self.parse_policy(self.policy(lines=("8", 2)))

    def test_rejects_boolean_policy_value(self):
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "integer"):
            self.parse_policy(self.policy(lines=(True, 2)))

    def test_rejects_zero_total_policy_metric(self):
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "greater than zero"):
            self.parse_policy(self.policy(branches=(0, 0)))

    def test_exact_line_and_branch_baselines_pass(self):
        REPORT.enforce_policy(
            REPORT.Counter(8, 2), REPORT.Counter(7, 3),
            {"LINE": REPORT.Counter(8, 2), "BRANCH": REPORT.Counter(7, 3)},
        )

    def test_improved_line_ratio_passes(self):
        REPORT.enforce_policy(
            REPORT.Counter(9, 1), REPORT.Counter(7, 3),
            {"LINE": REPORT.Counter(8, 2), "BRANCH": REPORT.Counter(7, 3)},
        )

    def test_improved_branch_ratio_passes(self):
        REPORT.enforce_policy(
            REPORT.Counter(8, 2), REPORT.Counter(8, 2),
            {"LINE": REPORT.Counter(8, 2), "BRANCH": REPORT.Counter(7, 3)},
        )

    def test_line_regression_fails(self):
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "LINE coverage regression"):
            REPORT.enforce_policy(
                REPORT.Counter(7, 3), REPORT.Counter(7, 3),
                {"LINE": REPORT.Counter(8, 2), "BRANCH": REPORT.Counter(7, 3)},
            )

    def test_branch_regression_fails(self):
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "BRANCH coverage regression"):
            REPORT.enforce_policy(
                REPORT.Counter(8, 2), REPORT.Counter(6, 4),
                {"LINE": REPORT.Counter(8, 2), "BRANCH": REPORT.Counter(7, 3)},
            )

    def test_same_displayed_percentage_exact_regression_fails(self):
        baseline = REPORT.Counter(10000, 1)
        current = REPORT.Counter(9999, 1)
        self.assertEqual(REPORT.percentage(baseline), REPORT.percentage(current))
        with self.assertRaisesRegex(
            REPORT.PolicyValidationError,
            r"9999 \* 10001 = 99999999 >= 10000 \* 10000 = 100000000 is false",
        ):
            REPORT.enforce_policy(
                current, REPORT.Counter(1, 1),
                {"LINE": baseline, "BRANCH": REPORT.Counter(1, 1)},
            )

    def test_current_zero_total_fails(self):
        with self.assertRaisesRegex(REPORT.PolicyValidationError, "LINE coverage regression"):
            REPORT.enforce_policy(
                REPORT.Counter(0, 0), REPORT.Counter(1, 1),
                {"LINE": REPORT.Counter(1, 1), "BRANCH": REPORT.Counter(1, 1)},
            )

    def test_default_execution_remains_non_blocking(self):
        result, stdout, stderr = self.run_main(
            '<counter type="LINE" missed="99" covered="1"/>'
            '<counter type="BRANCH" missed="99" covered="1"/>'
        )
        self.assertEqual(0, result)
        self.assertIn("JaCoCo coverage baseline", stdout)
        self.assertEqual("", stderr)

    def test_policy_execution_passes_exact_baseline(self):
        result, stdout, stderr = self.run_main(
            '<counter type="LINE" missed="2" covered="8"/>'
            '<counter type="BRANCH" missed="3" covered="7"/>', self.policy()
        )
        self.assertEqual(0, result)
        self.assertIn("coverage policy passed", stdout)
        self.assertEqual("", stderr)


if __name__ == "__main__":
    unittest.main()
