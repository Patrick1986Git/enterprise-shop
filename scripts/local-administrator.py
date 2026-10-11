#!/usr/bin/env python3
"""Manually promote one verified local User when no usable administrator exists."""

import argparse
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
from uuid import UUID

ROOT = Path(__file__).resolve().parents[1]
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\Z")


def validate(args):
    if not IDENTIFIER.fullmatch(args.database) or not IDENTIFIER.fullmatch(args.operator):
        raise ValueError("Database and operator identifiers are invalid")
    args.target = str(UUID(args.target))
    if args.host not in {"localhost", "127.0.0.1", "::1"}:
        socket_directory = Path(args.host)
        if not socket_directory.is_absolute() or not socket_directory.is_dir():
            raise ValueError("Only loopback hosts or a local Unix socket directory are supported")
    if not 0 < args.port < 65536 or not 0 < args.lock_timeout_ms < args.statement_timeout_ms <= 2_147_483_647:
        raise ValueError("Select positive bounded timeouts with lock timeout shorter than statement timeout")
    password_file = args.password_file
    metadata = password_file.lstat()
    if (not password_file.is_absolute() or not stat.S_ISREG(metadata.st_mode)
            or stat.S_IMODE(metadata.st_mode) != 0o600 or metadata.st_uid != os.getuid()
            or password_file.resolve().is_relative_to(ROOT)):
        raise ValueError("Password file must be owner-only, regular, and outside the repository")


def execute(args):
    environment = {key: value for key, value in os.environ.items() if not key.startswith("PG")}
    environment["PGPASSFILE"] = str(args.password_file.resolve())
    environment["PGAPPNAME"] = "enterprise-shop-local-administrator"
    environment["PGCONNECT_TIMEOUT"] = str(max(2, (args.statement_timeout_ms + 999) // 1000))
    command = ["psql", "-X", "-qAt", "--no-password", "--set=ON_ERROR_STOP=1",
               "--host", args.host, "--port", str(args.port), "--username", args.operator,
               "--dbname", args.database, "--set=target=" + args.target,
               "--set=expected_database=" + args.database,
               "--set=lock_timeout=" + str(args.lock_timeout_ms) + "ms",
               "--set=statement_timeout=" + str(args.statement_timeout_ms) + "ms"]
    result = subprocess.run(command, input="""BEGIN;
SET LOCAL lock_timeout = :'lock_timeout';
SET LOCAL statement_timeout = :'statement_timeout';
SELECT administrator_lifecycle.promote_zero_administrator(:'target'::uuid, :'expected_database', 'local');
COMMIT;
""", text=True, capture_output=True, env=environment, check=False)
    if result.returncode != 0:
        raise ValueError("Promotion failed; check owner-readable evidence before a confirmed retry")
    return str(UUID(result.stdout.strip()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--operator", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--password-file", type=Path, required=True)
    parser.add_argument("--lock-timeout-ms", type=int, required=True)
    parser.add_argument("--statement-timeout-ms", type=int, required=True)
    args = parser.parse_args()
    try:
        validate(args)
        if not sys.stdin.isatty():
            raise ValueError("A local operator terminal is required")
        confirmation = f"PROMOTE_ZERO_ADMIN {args.database} {args.target}"
        if input(f"After verifying your local account UUID, type {confirmation}: ") != confirmation:
            raise ValueError("Confirmation did not match; no operation was attempted")
        evidence = execute(args)
    except (ValueError, OSError, EOFError, subprocess.SubprocessError):
        print("Local promotion was not confirmed successful. Check inputs and owner-readable evidence before retrying.",
              file=sys.stderr)
        return 1
    print("Promotion committed. Evidence ID: " + evidence)
    return 0


if __name__ == "__main__":
    sys.exit(main())
