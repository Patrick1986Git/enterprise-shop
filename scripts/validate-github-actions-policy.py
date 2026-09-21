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
CONTAINER_SECURITY_JOB = re.compile(
    r"(?ms)^  container-security:\s*$\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)"
)
RESTORE_REHEARSAL_JOB = re.compile(
    r"(?ms)^  restore-rehearsal:\s*$\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)"
)
CHECKOUT_STEP = re.compile(
    r"(?ms)^      - name: Checkout\s*$\n(?P<body>.*?)(?=^      - |\Z)"
)
CHECKOUT_REF = re.compile(r"(?m)^          ref:\s*(?P<value>.+?)\s*$")
SCHEDULE_CHECKOUT_REF = re.compile(
    r"^\$\{\{\s*github\.event_name\s*==\s*(['\"])schedule\1\s*&&\s*"
    r"(['\"])master\2\s*\|\|\s*github\.ref\s*\}\}$"
)
OPENAPI_BASELINE_CHECKOUT = re.compile(
    r"(?ms)^      - name: Checkout protected-master OpenAPI baseline source\s*$\n"
    r"(?P<body>.*?)(?=^      - |\Z)"
)
OPENAPI_BASELINE_GENERATION = re.compile(
    r"(?ms)^      - name: Generate protected-master OpenAPI baseline\s*$\n"
    r"(?P<body>.*?)(?=^      - |\Z)"
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


def container_security_checkout_ref(path):
    """Return the validated container-security checkout expression, when present."""
    contents = path.read_text(encoding="utf-8")
    job_match = CONTAINER_SECURITY_JOB.search(contents)
    if not job_match:
        return None
    checkout_match = CHECKOUT_STEP.search(job_match.group("body"))
    if not checkout_match:
        return None
    ref_match = CHECKOUT_REF.search(checkout_match.group("body"))
    return ref_match.group("value").strip() if ref_match else None


def restore_rehearsal_checkout_ref(path):
    contents = path.read_text(encoding="utf-8")
    job_match = RESTORE_REHEARSAL_JOB.search(contents)
    if not job_match:
        return None
    checkout_match = CHECKOUT_STEP.search(job_match.group("body").replace(
        "- name: Checkout restore rehearsal source", "- name: Checkout"
    ))
    if not checkout_match:
        return None
    ref_match = CHECKOUT_REF.search(checkout_match.group("body"))
    return ref_match.group("value").strip() if ref_match else None


def resolve_container_security_checkout_ref(expression, event_name, github_ref):
    """Resolve the supported schedule-only checkout expression for policy tests."""
    if not expression or not SCHEDULE_CHECKOUT_REF.fullmatch(expression):
        raise ValueError("unsupported container-security checkout ref expression")
    return "master" if event_name == "schedule" else github_ref


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
        if path.name == "ci.yml":
            contents = path.read_text(encoding="utf-8")
            checkout_ref = container_security_checkout_ref(path)
            if not checkout_ref or not SCHEDULE_CHECKOUT_REF.fullmatch(checkout_ref):
                violations.append(
                    f"{path}: container-security checkout must use master only for schedule "
                    "and github.ref for workflow_dispatch, pull_request, and push"
                )
            rehearsal_ref = restore_rehearsal_checkout_ref(path)
            if not rehearsal_ref or not SCHEDULE_CHECKOUT_REF.fullmatch(rehearsal_ref):
                violations.append(
                    f"{path}: restore-rehearsal checkout must use protected master for schedule "
                    "and github.ref for protected push and explicit workflow_dispatch refs"
                )
            rehearsal = RESTORE_REHEARSAL_JOB.search(contents)
            if (not rehearsal
                    or "if: github.event_name == 'push' || github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'" not in rehearsal.group("body")
                    or "run: ./scripts/restore-rehearsal.sh" not in rehearsal.group("body")
                    or "run: ./scripts/historical-forward-restore-rehearsal.sh" not in rehearsal.group("body")):
                violations.append(
                    f"{path}: restore-rehearsal must be limited to push, schedule, and workflow_dispatch "
                    "and invoke both repository-owned restore scripts"
                )
            baseline_checkout = OPENAPI_BASELINE_CHECKOUT.search(contents)
            required = (
                "if: github.event_name == 'pull_request'",
                "repository: ${{ github.event.pull_request.base.repo.full_name }}",
                "ref: ${{ github.event.pull_request.base.sha }}",
                "path: target/openapi-baseline-source",
                "persist-credentials: false",
            )
            if not baseline_checkout or any(item not in baseline_checkout.group("body") for item in required):
                violations.append(
                    f"{path}: OpenAPI baseline checkout must be PR-only, use the PR base repository/SHA, "
                    "use the isolated baseline path, and disable persisted credentials"
                )
            baseline_generation = OPENAPI_BASELINE_GENERATION.search(contents)
            if (not baseline_generation
                    or "SPRING_PROFILES_ACTIVE: test" not in baseline_generation.group("body")):
                violations.append(
                    f"{path}: OpenAPI baseline generation must use the canonical test Spring profile"
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
