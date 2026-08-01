#!/usr/bin/env python3
"""Validate immutable GitHub Action references and checkout credential handling."""

import argparse
import re
import sys
from pathlib import Path


FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
USES_LINE = re.compile(r"^(?P<indent>\s*)-?\s*uses:\s*(?P<value>[^#]+?)(?:\s+#.*)?$")
BLOCK_SCALAR = re.compile(r"^(?P<indent>\s*)[^#\n]+:\s*[>|][+-]?\s*(?:#.*)?$")
WITH_LINE = re.compile(r"^(?P<indent>\s*)with:\s*(?:#.*)?$")
PERSIST_LINE = re.compile(
    r"^(?P<indent>\s*)persist-credentials:\s*(?P<value>[^#]+?)(?:\s+#.*)?$"
)


def workflow_files(workflows_dir):
    return sorted((*workflows_dir.glob("*.yml"), *workflows_dir.glob("*.yaml")))


def action_references(path):
    """Yield (line number, action reference, step indent, lines) outside block scalars."""
    lines = path.read_text(encoding="utf-8").splitlines()
    block_indent = None
    for line_number, line in enumerate(lines, start=1):
        indent = len(line) - len(line.lstrip())
        if block_indent is not None:
            if not line.strip() or indent > block_indent:
                continue
            block_indent = None
        block_match = BLOCK_SCALAR.match(line)
        if block_match:
            block_indent = len(block_match.group("indent"))
            continue
        uses_match = USES_LINE.match(line)
        if uses_match:
            value = uses_match.group("value").strip().strip("'\"")
            yield line_number, value, len(uses_match.group("indent")), lines


def checkout_persists_credentials(lines, uses_line_number, uses_indent):
    with_indent = None
    for line in lines[uses_line_number:]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if indent < uses_indent or (indent == uses_indent and line.lstrip().startswith("- ")):
            break
        with_match = WITH_LINE.match(line)
        if with_match and indent >= uses_indent:
            with_indent = indent
            continue
        if with_indent is not None:
            if indent <= with_indent:
                with_indent = None
                continue
            persist_match = PERSIST_LINE.match(line)
            if persist_match and persist_match.group("value").strip().strip("'\"") == "false":
                return True
    return False


def validate_workflows(workflows_dir):
    violations = []
    for path in workflow_files(workflows_dir):
        for line_number, reference, uses_indent, lines in action_references(path):
            if reference.startswith("./"):
                continue
            action, separator, revision = reference.rpartition("@")
            if not separator or not action or not FULL_SHA.fullmatch(revision):
                violations.append(
                    f"{path}:{line_number}: external action must use a full 40-character "
                    f"lowercase commit SHA: {reference}"
                )
                continue
            if action.lower() == "actions/checkout" and not checkout_persists_credentials(
                lines, line_number, uses_indent
            ):
                violations.append(
                    f"{path}:{line_number}: {reference} must explicitly set "
                    "persist-credentials: false"
                )
    return violations


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "workflows_dir",
        nargs="?",
        type=Path,
        default=Path(".github/workflows"),
    )
    args = parser.parse_args()

    violations = validate_workflows(args.workflows_dir)
    if violations:
        for violation in violations:
            print(violation, file=sys.stderr)
        return 1
    print(f"GitHub Actions policy passed for {args.workflows_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
