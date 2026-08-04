import importlib.util
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
