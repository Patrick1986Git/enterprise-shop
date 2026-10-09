import copy
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "validate-codeql-python-coverage.py"
SPEC = importlib.util.spec_from_file_location("codeql_python_coverage", SCRIPT)
COVERAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COVERAGE)
HEAD = "a" * 40


class CodeQLPythonCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root / "scripts/security.py"
        self.source.parent.mkdir()
        self.source.write_text("raise ValueError('fail closed')\n")
        self.archive = self.root / "src.zip"
        self.inventory = b"scripts/security.py\0"
        self.extracted = {"#select": {"tuples": [[{"url": {"uri": self.source.as_uri()}}, ""]]}}
        self.sarif = {"version": "2.1.0", "runs": [{
            "tool": {"driver": {"name": "CodeQL", "rules": [{"id": "py/command-line-injection"}]}},
            "invocations": [{"executionSuccessful": True}], "results": []}]}
        self.write_archive()

    def write_archive(self, files=None):
        if files is None:
            files = {self.source.as_posix().lstrip("/"): self.source.read_bytes()}
        with zipfile.ZipFile(self.archive, "w") as output:
            for name, data in files.items():
                output.writestr(name, data)

    def validate(self):
        return COVERAGE.validate_coverage(self.root, self.inventory, self.archive, self.sarif, HEAD, self.extracted)

    def test_actual_source_bytes_and_successful_queries_are_recorded(self):
        report = self.validate()
        self.assertEqual(HEAD, report["head_sha"])
        self.assertEqual(1, report["tracked_python_files"])
        self.assertEqual(1, report["extracted_python_files"])
        self.assertEqual("scripts/security.py", report["sources"][0]["path"])

    def test_codeql_query_pack_rules_in_sarif_extensions_are_verified(self):
        tool = self.sarif["runs"][0]["tool"]
        tool["extensions"] = [{"name": "codeql/python-queries", "rules": tool["driver"].pop("rules")}]
        self.assertEqual(1, self.validate()["security_query_rules"])

    def test_empty_extraction_cannot_pass_a_green_scan(self):
        self.write_archive({})
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous extracted"):
            self.validate()

    def test_future_owned_source_outside_scripts_must_also_be_extracted(self):
        future = self.root / "future/tool.py"
        future.parent.mkdir()
        future.write_text("print('future source')\n")
        self.inventory += b"future/tool.py\0"
        self.extracted["#select"]["tuples"].append([{"url": {"uri": future.as_uri()}}, ""])
        with self.assertRaisesRegex(ValueError, "future/tool.py"):
            self.validate()
        self.write_archive({p.as_posix().lstrip("/"): p.read_bytes() for p in (self.source, future)})
        self.assertEqual(2, self.validate()["extracted_python_files"])

    def test_wrong_checkout_or_modified_source_cannot_pass(self):
        self.write_archive({"other-checkout/scripts/security.py": self.source.read_bytes()})
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous"):
            self.validate()
        self.write_archive()
        self.source.write_text("changed = True\n")
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            self.validate()

    def test_archived_but_unextracted_source_cannot_pass(self):
        self.extracted["#select"]["tuples"][0][0]["url"]["uri"] = (self.root / "other.py").as_uri()
        with self.assertRaisesRegex(ValueError, "not present in extracted-file query"):
            self.validate()

    def test_empty_or_malformed_extracted_file_query_fails(self):
        for evidence in ({}, {"#select": {"tuples": []}}, {"#select": {"tuples": [["summary", ""]]}}):
            with self.subTest(evidence=evidence):
                self.extracted = evidence
                with self.assertRaises(ValueError):
                    self.validate()

    def test_untracked_generated_files_cannot_replace_owned_sources(self):
        self.write_archive({"tmp/generated.py": b"print('generated')\n"})
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous"):
            self.validate()

    def test_missing_truncated_or_duplicate_inventory_fails(self):
        for inventory in (b"", b"scripts/security.py", self.inventory * 2):
            with self.subTest(inventory=inventory):
                self.inventory = inventory
                with self.assertRaises(ValueError):
                    self.validate()

    def test_untrusted_path_or_link_fails_without_archive_extraction(self):
        for path in (b"../escape.py\0", b"/absolute.py\0", b"scripts/security.sh\0"):
            with self.subTest(path=path):
                self.inventory = path
                with self.assertRaisesRegex(ValueError, "Invalid tracked"):
                    self.validate()
        self.inventory = b"scripts/security.py\0"
        self.source.unlink()
        self.source.symlink_to(self.root / "missing.py")
        with self.assertRaisesRegex(ValueError, "Missing or linked"):
            self.validate()

    def test_failed_or_non_python_query_execution_fails(self):
        original = copy.deepcopy(self.sarif)
        for change in (lambda r: r.update(invocations=[]),
                       lambda r: r["invocations"][0].update(executionSuccessful=False),
                       lambda r: r["tool"]["driver"].update(rules=[{"id": "java/injection"}]),
                       lambda r: r.pop("results")):
            self.sarif = copy.deepcopy(original)
            change(self.sarif["runs"][0])
            with self.assertRaises(ValueError):
                self.validate()

    def test_findings_remain_visible_for_review(self):
        self.sarif["runs"][0]["results"] = [{"ruleId": "py/command-line-injection"}]
        self.assertEqual(1, self.validate()["findings"])


if __name__ == "__main__":
    unittest.main()
