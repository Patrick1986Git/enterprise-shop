import argparse
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from uuid import uuid4

SPEC = importlib.util.spec_from_file_location("local_administrator", Path(__file__).parents[1] / "local-administrator.py")
CLI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLI)


class LocalAdministratorTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.password_file = Path(self.directory.name) / "password-file"
        self.password_file.write_text("synthetic-secret-must-not-be-printed")
        self.password_file.chmod(0o600)
        self.args = argparse.Namespace(host="127.0.0.1", port=5433, database="local_test", operator="local_operator",
            target=str(uuid4()), password_file=self.password_file, lock_timeout_ms=1000, statement_timeout_ms=5000)

    def test_accepts_owner_only_external_password_file_and_immutable_uuid(self):
        CLI.validate(self.args)
        self.assertEqual(str(CLI.UUID(self.args.target)), self.args.target)

    def test_rejects_remote_host_bad_uuid_unbounded_timeout_and_identifiers(self):
        for key, value in (("host", "remote.example"), ("target", "unknown"), ("database", "a;sql"),
                ("operator", "a;sql"), ("port", 0), ("lock_timeout_ms", 0), ("statement_timeout_ms", 2**31)):
            with self.subTest(key=key):
                args = argparse.Namespace(**vars(self.args))
                setattr(args, key, value)
                with self.assertRaises(ValueError):
                    CLI.validate(args)

    def test_rejects_shared_symlinked_or_repository_password_files(self):
        self.password_file.chmod(0o644)
        with self.assertRaises(ValueError):
            CLI.validate(self.args)
        self.password_file.chmod(0o600)
        with mock.patch.object(CLI, "ROOT", self.password_file.parent):
            with self.assertRaises(ValueError):
                CLI.validate(self.args)
        link = self.password_file.parent / "link"
        link.symlink_to(self.password_file)
        self.args.password_file = link
        with self.assertRaises(ValueError):
            CLI.validate(self.args)

    def test_execution_uses_one_transaction_no_actor_label_and_clean_libpq_environment(self):
        receipt = str(uuid4())
        with mock.patch.dict(os.environ, {"PGPASSWORD": "synthetic-secret", "PGSERVICE": "wrong",
                "PGHOSTADDR": "remote", "PGOPTIONS": "wrong"}), mock.patch.object(CLI.subprocess, "run") as run:
            run.return_value = argparse.Namespace(returncode=0, stdout=receipt + "\n", stderr="")
            self.assertEqual(CLI.execute(self.args), receipt)
        call = run.call_args
        environment = call.kwargs["env"]
        self.assertEqual(environment["PGPASSFILE"], str(self.password_file))
        self.assertNotIn("PGPASSWORD", environment)
        self.assertNotIn("PGHOSTADDR", environment)
        self.assertNotIn("PGOPTIONS", environment)
        self.assertNotIn("PGSERVICE", environment)
        self.assertTrue(call.kwargs["input"].startswith("BEGIN;"))
        self.assertTrue(call.kwargs["input"].endswith("COMMIT;\n"))
        self.assertNotIn("actor", call.kwargs["input"])
        self.assertIn("--no-password", call.args[0])
        self.assertNotIn("synthetic-secret", " ".join(call.args[0]))

    def command(self):
        return ["local-administrator.py", "--host", self.args.host, "--port", str(self.args.port),
            "--database", self.args.database, "--operator", self.args.operator, "--target", self.args.target,
            "--password-file", str(self.password_file), "--lock-timeout-ms", "1000", "--statement-timeout-ms", "5000"]

    def test_missing_terminal_and_wrong_confirmation_never_connect(self):
        for terminal, confirmation in ((False, "unused"), (True, "wrong")):
            with mock.patch.object(CLI.sys, "argv", self.command()), mock.patch.object(CLI.sys.stdin, "isatty", return_value=terminal), \
                    mock.patch("builtins.input", return_value=confirmation), mock.patch.object(CLI, "execute") as execute, \
                    contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(CLI.main(), 1)
                execute.assert_not_called()

    def test_success_requires_exact_confirmation_and_reports_only_receipt(self):
        receipt = str(uuid4())
        with mock.patch.object(CLI.sys, "argv", self.command()), mock.patch.object(CLI.sys.stdin, "isatty", return_value=True), \
                mock.patch("builtins.input", return_value=f"PROMOTE_ZERO_ADMIN {self.args.database} {self.args.target}"), \
                mock.patch.object(CLI, "execute", return_value=receipt), contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(CLI.main(), 0)
            self.assertEqual(output.getvalue(), "Promotion committed. Evidence ID: " + receipt + "\n")

    def test_database_failure_does_not_print_driver_messages_or_credentials(self):
        with mock.patch.object(CLI.sys, "argv", self.command()), mock.patch.object(CLI.sys.stdin, "isatty", return_value=True), \
                mock.patch("builtins.input", return_value=f"PROMOTE_ZERO_ADMIN {self.args.database} {self.args.target}"), \
                mock.patch.object(CLI.subprocess, "run", return_value=argparse.Namespace(returncode=1, stdout="", stderr="synthetic-secret")), \
                contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertEqual(CLI.main(), 1)
            self.assertNotIn("synthetic-secret", error.getvalue())


if __name__ == "__main__":
    unittest.main()
