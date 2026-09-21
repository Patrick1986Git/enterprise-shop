#!/usr/bin/env python3
"""Fail-closed PR routing for the PostgreSQL restore rehearsal."""

import argparse
import os
import subprocess
import sys
from pathlib import Path


EXACT_RELEVANT_PATHS = {
    ".dockerignore",
    ".github/workflows/ci.yml",
    "Dockerfile",
    "pom.xml",
    "mvnw",
    "mvnw.cmd",
    "scripts/historical-forward-restore-fixture.sql",
    "scripts/historical-forward-restore-rehearsal.sh",
    "scripts/restore-rehearsal-fixture.sql",
    "scripts/restore-rehearsal.sh",
    "scripts/restore_pr_scope.py",
}

RELEVANT_PREFIXES = (
    ".mvn/",
    "docker/",
    "scripts/lib/",
    "src/main/java/",
    "src/main/resources/",
)


def path_requires_restore(path):
    """Return whether a repository path can affect either restore scenario."""
    if not isinstance(path, str) or not path or "\x00" in path:
        raise ValueError("changed path evidence is malformed")
    normalized = Path(path).as_posix()
    if normalized.startswith("/") or normalized == ".." or normalized.startswith("../"):
        raise ValueError("changed path is not repository-relative")
    return normalized in EXACT_RELEVANT_PATHS or normalized.startswith(RELEVANT_PREFIXES)


def decide(paths):
    """Return (run, reason), failing closed for empty or malformed evidence."""
    if not isinstance(paths, (list, tuple)) or not paths:
        return True, "changed-file evidence is unavailable or empty"
    try:
        relevant = [path for path in paths if path_requires_restore(path)]
    except (TypeError, ValueError) as error:
        return True, str(error)
    if relevant:
        return True, f"restore-relevant path changed: {relevant[0]}"
    return False, "all changed paths are outside the restore execution boundary"


def changed_paths(base_sha, head_sha):
    """Read an exact, NUL-delimited base/head diff without trusting branch names."""
    if not base_sha or not head_sha:
        raise ValueError("base and head SHAs are required")
    result = subprocess.run(
        [
            "git",
            "diff",
            "--name-only",
            "--no-renames",
            "-z",
            "--diff-filter=ACDMRTUXB",
            base_sha,
            head_sha,
            "--",
        ],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return [item.decode("utf-8") for item in result.stdout.split(b"\0") if item]


def write_output(run_restore, reason, output_path):
    value = "true" if run_restore else "false"
    with output_path.open("a", encoding="utf-8") as output:
        output.write(f"run_restore={value}\n")
    print(f"run_restore={value}: {reason}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-sha", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument(
        "--github-output",
        type=Path,
        default=Path(os.environ["GITHUB_OUTPUT"]) if "GITHUB_OUTPUT" in os.environ else None,
    )
    args = parser.parse_args()
    if args.github_output is None:
        parser.error("--github-output or GITHUB_OUTPUT is required")

    try:
        paths = changed_paths(args.base_sha, args.head_sha)
        run_restore, reason = decide(paths)
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError) as error:
        run_restore, reason = True, f"changed-file detection failed closed: {error}"
    write_output(run_restore, reason, args.github_output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
