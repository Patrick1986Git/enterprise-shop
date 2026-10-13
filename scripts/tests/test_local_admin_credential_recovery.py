import contextlib
import importlib.util
import io
import os
from pathlib import Path
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("local_admin_recovery", Path(__file__).parents[1] / "local-admin-credential-recovery.py")
CLI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLI)


class LocalAdminCredentialRecoveryTest(unittest.TestCase):
    def test_missing_terminal_or_jar_never_launches(self):
        with mock.patch.object(CLI.sys.stdin, "isatty", return_value=False), mock.patch.object(CLI.subprocess, "run") as run, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(CLI.main(), 1)
            run.assert_not_called()

    def test_launcher_removes_inherited_jvm_and_libpq_overrides(self):
        with mock.patch.object(CLI.sys.stdin, "isatty", return_value=True), mock.patch.object(CLI.sys.stdout, "isatty", return_value=True), \
                mock.patch.object(CLI.sys.stderr, "isatty", return_value=True), mock.patch.object(CLI.Path, "is_file", return_value=True), \
                mock.patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": "unsafe", "PGPASSWORD": "synthetic", "LOADER_MAIN": "unsafe", "CLASSPATH": "unsafe"}), \
                mock.patch.object(CLI.sys, "argv", ["tool", "status"]), mock.patch.object(CLI.subprocess, "run") as run:
            run.return_value.returncode = 0
            self.assertEqual(CLI.main(), 0)
            call = run.call_args
            for name in ["JAVA_TOOL_OPTIONS", "PGPASSWORD", "LOADER_MAIN", "CLASSPATH"]:
                self.assertNotIn(name, call.kwargs["env"])
            self.assertIn("org.springframework.boot.loader.launch.PropertiesLauncher", call.args[0])
            self.assertFalse(call.kwargs["check"])

    def test_launch_failure_has_no_driver_diagnostics(self):
        with mock.patch.object(CLI.sys.stdin, "isatty", return_value=True), mock.patch.object(CLI.sys.stdout, "isatty", return_value=True), \
                mock.patch.object(CLI.sys.stderr, "isatty", return_value=True), mock.patch.object(CLI.Path, "is_file", return_value=True), \
                mock.patch.object(CLI.subprocess, "run", side_effect=OSError("synthetic-sensitive-diagnostic")), contextlib.redirect_stderr(io.StringIO()) as output:
            self.assertEqual(CLI.main(), 1)
            self.assertNotIn("synthetic-sensitive-diagnostic", output.getvalue())
