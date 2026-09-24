import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "validate-maven-wrapper.py"
SPEC = importlib.util.spec_from_file_location("validate_maven_wrapper", SCRIPT)
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)


class MavenWrapperPolicyTest(unittest.TestCase):
    def validate(self, contents):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "maven-wrapper.properties"
            path.write_text(contents, encoding="utf-8")
            return VALIDATOR.validate(path)

    def valid_properties(self):
        return (
            "wrapperVersion=3.3.4\n"
            "distributionType=only-script\n"
            "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/"
            "apache-maven/3.9.16/apache-maven-3.9.16-bin.zip\n"
            f"distributionSha256Sum={VALIDATOR.EXPECTED_SHA256}\n"
        )

    def test_accepts_reviewed_distribution_and_checksum(self):
        self.assertEqual([], self.validate(self.valid_properties()))

    def test_rejects_missing_or_blank_checksum(self):
        missing = self.valid_properties().replace(
            f"distributionSha256Sum={VALIDATOR.EXPECTED_SHA256}\n", ""
        )
        blank = self.valid_properties().replace(VALIDATOR.EXPECTED_SHA256, "")

        self.assertIn("exactly 64 hexadecimal", " ".join(self.validate(missing)))
        self.assertIn("exactly 64 hexadecimal", " ".join(self.validate(blank)))

    def test_rejects_malformed_or_wrong_checksum(self):
        malformed = self.valid_properties().replace(VALIDATOR.EXPECTED_SHA256, "abc123")
        wrong = self.valid_properties().replace(VALIDATOR.EXPECTED_SHA256, "0" * 64)

        self.assertIn("exactly 64 hexadecimal", " ".join(self.validate(malformed)))
        self.assertIn("reviewed digest", " ".join(self.validate(wrong)))

    def test_rejects_http_distribution_url(self):
        properties = self.valid_properties().replace("https://", "http://")

        self.assertIn("must use HTTPS", " ".join(self.validate(properties)))

    def test_rejects_unreviewed_distribution_archive(self):
        properties = self.valid_properties().replace("3.9.16", "3.9.17")

        self.assertIn("reviewed archive", " ".join(self.validate(properties)))


if __name__ == "__main__":
    unittest.main()
