import importlib.util
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).parents[1] / "test-gosu-runtime.py"
SPEC = importlib.util.spec_from_file_location("gosu_runtime", SCRIPT)
RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNTIME)

ACCOUNT_RECORDS = ("root:x:0:0:root:/root:/bin/sh\n"
                   "fixture:x:12345:23456:fixture:/tmp/fixture:/bin/sh\n")
GROUP_RECORDS = ("root:x:0:\nfixturegroup:x:23456:\n"
                 "alpha:x:12346:fixture\nbeta:x:12347:fixture\n")


class GosuRuntimeFixtureTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.checks = RUNTIME.RuntimeChecks("unused-test-image")

    def assert_fixture(self, directory, account_records, group_records):
        self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o755)
        self.assertEqual({path.name for path in directory.iterdir()}, {"passwd", "group"})
        for name, records in (("passwd", account_records), ("group", group_records)):
            path = directory / name
            self.assertEqual(path.read_bytes(), records.encode())
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o644)

    def assert_rejected_before_writes(self, account_records, group_records):
        directory = self.root / "rejected"
        with mock.patch.object(Path, "write_text") as write:
            with self.assertRaisesRegex(AssertionError, "synthetic fixture records"):
                self.checks.fixture(directory, account_records, group_records)
        write.assert_not_called()
        self.assertFalse(directory.exists())

    def test_default_records_preserve_exact_bytes_filenames_and_modes(self):
        directory = self.checks.fixture(self.root / "default")
        self.assert_fixture(directory, ACCOUNT_RECORDS, GROUP_RECORDS)

    def test_all_malformed_parser_cases_preserve_exact_input_bytes_and_modes(self):
        group_record = "other:x:23457:notfixture\n"
        account_record = "other:x:23457:23457:other:/tmp:/bin/sh\n"
        expected = {
            "group-line-limit": (
                ACCOUNT_RECORDS,
                GROUP_RECORDS + "huge:x:12348:" + "x" * (1024 * 1024) + "\n",
                "token too long",
            ),
            "passwd-line-limit": (
                ACCOUNT_RECORDS + "x" * (128 * 1024) + "\n",
                GROUP_RECORDS,
                "token too long",
            ),
            "group-file-limit": (
                ACCOUNT_RECORDS,
                GROUP_RECORDS + group_record * ((10 * 1024 * 1024 // len(group_record)) + 1),
                "file exceeds 10485760 bytes",
            ),
            "passwd-file-limit": (
                ACCOUNT_RECORDS + account_record * ((10 * 1024 * 1024 // len(account_record)) + 1),
                GROUP_RECORDS,
                "file exceeds 10485760 bytes",
            ),
        }
        with mock.patch.object(self.checks, "bounded_error") as bounded_error:
            self.checks.malformed_accounts(self.root)
        self.assertEqual(len(bounded_error.call_args_list), 4)
        self.assertEqual({call.args[0] for call in bounded_error.call_args_list}, set(expected))
        for call in bounded_error.call_args_list:
            name, account_records, group_records, error, directory = call.args
            with self.subTest(name=name):
                self.assertEqual((account_records, group_records, error), expected[name])
                self.assertEqual(directory, self.root)
                fixture = self.checks.fixture(directory / name, account_records, group_records)
                self.assert_fixture(fixture, *expected[name][:2])

    def test_account_records_reject_every_non_x_placeholder_before_writes(self):
        for field in ("fixture-value", "", "*", "!"):
            with self.subTest(field=field):
                account_records = f"fixture:{field}:12345:23456:fixture:/tmp/fixture:/bin/sh\n"
                self.assert_rejected_before_writes(account_records, GROUP_RECORDS)

    def test_group_records_reject_every_non_x_placeholder_before_either_file_is_written(self):
        for field in ("fixture-value", "", "*", "!"):
            with self.subTest(field=field):
                group_records = f"fixturegroup:{field}:23456:fixture\n"
                self.assert_rejected_before_writes(ACCOUNT_RECORDS, group_records)

    def test_invalid_record_shapes_are_rejected_before_writes(self):
        variants = (
            ("", GROUP_RECORDS),
            (ACCOUNT_RECORDS, ""),
            (ACCOUNT_RECORDS + "\n", GROUP_RECORDS),
            (ACCOUNT_RECORDS, GROUP_RECORDS + "\n"),
            ("fixture:x:12345:23456:fixture:/tmp/fixture\n", GROUP_RECORDS),
            ("fixture:x:12345:23456:fixture:/tmp/fixture:/bin/sh:extra\n", GROUP_RECORDS),
            (ACCOUNT_RECORDS, "fixturegroup:x:23456\n"),
            (ACCOUNT_RECORDS, "fixturegroup:x:23456:fixture:extra\n"),
        )
        for account_records, group_records in variants:
            with self.subTest(account_length=len(account_records), group_length=len(group_records)):
                self.assert_rejected_before_writes(account_records, group_records)

    def test_only_exact_128_kib_x_record_gets_the_malformed_account_exception(self):
        for record in ("x" * (128 * 1024 - 1), "x" * (128 * 1024 + 1), "x" * (128 * 1024 - 1) + "y"):
            with self.subTest(length=len(record), ending=record[-1]):
                self.assert_rejected_before_writes(ACCOUNT_RECORDS + record + "\n", GROUP_RECORDS)
        self.assert_rejected_before_writes(ACCOUNT_RECORDS, GROUP_RECORDS + "x" * (128 * 1024) + "\n")


if __name__ == "__main__":
    unittest.main()
