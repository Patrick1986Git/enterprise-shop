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
RESTORE_PR_SCOPE_JOB = re.compile(
    r"(?ms)^  restore-pr-scope:\s*$\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)"
)
CHECKOUT_STEP = re.compile(
    r"(?ms)^      - name: Checkout\s*$\n(?P<body>.*?)(?=^      - |\Z)"
)
CHECKOUT_REF = re.compile(r"(?m)^          ref:\s*(?P<value>.+?)\s*$")
SCHEDULE_CHECKOUT_REF = re.compile(
    r"^\$\{\{\s*github\.event_name\s*==\s*(['\"])schedule\1\s*&&\s*"
    r"(['\"])master\2\s*\|\|\s*github\.event\.pull_request\.head\.sha\s*\|\|\s*github\.ref\s*\}\}$"
)
RESTORE_CHECKOUT_REF = re.compile(
    r"^\$\{\{\s*github\.event_name\s*==\s*(['\"])schedule\1\s*&&\s*"
    r"(['\"])master\2\s*\|\|\s*github\.event_name\s*==\s*(['\"])pull_request\3\s*&&\s*"
    r"github\.sha\s*\|\|\s*github\.ref\s*\}\}$"
)
OPENAPI_BASELINE_CHECKOUT = re.compile(
    r"(?ms)^      - name: Checkout protected-master OpenAPI baseline source\s*$\n"
    r"(?P<body>.*?)(?=^      - |\Z)"
)
OPENAPI_BASELINE_GENERATION = re.compile(
    r"(?ms)^      - name: Generate protected-master OpenAPI baseline\s*$\n"
    r"(?P<body>.*?)(?=^      - |\Z)"
)
MIGRATION_COMPATIBILITY = re.compile(
    r"(?ms)^      - name: Enforce Flyway rolling-compatibility decision boundary\s*$\n"
    r"(?P<body>.*?)(?=^      - |\Z)"
)
DEPENDENCY_REVIEW_JOB = re.compile(
    r"(?ms)^  dependency-review:\s*$\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)"
)
BUILD_JOB = re.compile(
    r"(?ms)^  build:\s*$\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)"
)
CODEQL_SHA = "2892aa5e19bbd11bc0cff5427e3b750a04d9e3c2"
CODEQL_JOB = re.compile(
    r"(?ms)^  (?P<id>analyze(?:-python)?):\s*$\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)"
)


def validate_codeql_workflow(path):
    """Protect the reviewed independent source-analysis and execution boundaries."""
    contents = path.read_text(encoding="utf-8")
    errors = []
    triggers = ("on:\n  pull_request:\n    branches:\n      - master\n  push:\n"
                "    branches:\n      - master\n  schedule:\n    - cron: \"37 3 * * 3\"\n"
                "  workflow_dispatch:")
    if triggers not in contents or "pull_request_target" in contents:
        errors.append("CodeQL must retain PR/master push, schedule and explicit dispatch triggers")
    defaults = re.findall(r"(?m)^permissions:\n((?:  \S[^\n]*\n)+)", contents)
    if defaults != ["  contents: read\n"]:
        errors.append("CodeQL default workflow permissions must remain contents: read")
    matches = list(CODEQL_JOB.finditer(contents))
    jobs = {match['id']: match['body'] for match in matches}
    job_ids = re.findall(r"(?m)^  ([A-Za-z0-9_-]+):\s*$", contents.split("jobs:\n", 1)[-1])
    if len(matches) != 2 or job_ids != ["analyze", "analyze-python"]:
        errors.append("CodeQL requires independent Java/Kotlin and Python jobs")
    for job_id, body in jobs.items():
        language = "python" if job_id == "analyze-python" else "java-kotlin"
        mode = "none" if language == "python" else "manual"
        required = (f"          languages: {language}\n", f"          build-mode: {mode}\n",
                    "          queries: security-extended\n",
                    "    permissions:\n      contents: read\n      security-events: write\n",
                    "          persist-credentials: false\n",
                    "          ref: ${{ github.event_name == 'schedule' && 'master' || "
                    "github.event.pull_request.head.sha || github.ref }}\n",
                    f"uses: github/codeql-action/init@{CODEQL_SHA}",
                    f"uses: github/codeql-action/analyze@{CODEQL_SHA}")
        if any(item not in body for item in required):
            errors.append(f"CodeQL {language} must retain reviewed action, mode, queries, permissions and checkout")
        if re.search(r"(?m)^\s+(?:if|needs|container|services|secrets):", body):
            errors.append(f"CodeQL {language} must remain unconditional and isolated")
        if re.search(r"(?m)^\s+(?:config|config-file|source-root|paths|paths-ignore|packs|skip-queries):", body):
            errors.append(f"CodeQL {language} source and security query coverage cannot be restricted")
        permissions = re.findall(r"(?m)^    permissions:\n((?:      \S[^\n]*\n)+)", body)
        upload = re.findall(r"(?m)^          upload: *([^\n#]+)", body)
        if (permissions != ["      contents: read\n      security-events: write\n"]
                or any(value.strip() != "always" for value in upload)):
            errors.append(f"CodeQL {language} must execute and upload with least privilege")
        if language == "java-kotlin":
            if any(item not in body for item in (
                    "          java-version: '21'", "run: python scripts/build-ryuk-candidate.py",
                    "run: ./mvnw -B clean verify", "DOCKER_HOST: unix:///var/run/docker.sock",
                    "SPRING_PROFILES_ACTIVE: test")):
                errors.append("CodeQL Java/Kotlin requires reviewed Ryuk and full Java 21 Maven verification")
            procedure = (f"uses: github/codeql-action/init@{CODEQL_SHA}",
                         "run: python scripts/build-ryuk-candidate.py", "run: ./mvnw -B clean verify",
                         f"uses: github/codeql-action/analyze@{CODEQL_SHA}")
            positions = [body.find(item) for item in procedure]
            if any(position < 0 for position in positions) or positions != sorted(positions):
                errors.append("CodeQL Java/Kotlin must populate its database between initialization and analysis")
            if re.search(r"(?m)^\s+category:", body):
                errors.append("CodeQL Java/Kotlin must preserve its existing SARIF category")
        else:
            if any(item not in body for item in (
                    "          category: /language:python\n", "          upload: always\n",
                    "          wait-for-processing: true\n",
                    "git ls-files -z -- '*.py'", "python scripts/validate-codeql-python-coverage.py",
                    "bqrs decode --format=json --entities=string,url",
                    "          name: python-codeql-evidence\n", "          if-no-files-found: error\n")):
                errors.append("CodeQL Python requires distinct processed SARIF and complete source-extraction evidence")
            steps = re.split(r"(?m)^      - ", body)[1:]
            scan = next((i for i, step in enumerate(steps) if f"uses: github/codeql-action/analyze@{CODEQL_SHA}" in step), len(steps))
            actions = re.findall(r"(?m)^        uses: ([^@\s]+)@", body)
            if (any(re.search(r"(?m)^        run:", step) for step in steps[:scan + 1])
                    or actions != ["actions/checkout", "github/codeql-action/init",
                                   "github/codeql-action/analyze", "actions/upload-artifact"]
                    or any(token in body for token in ("docker", "mvnw", "pip ", "secrets.", "sudo "))):
                errors.append("CodeQL Python must not build, install dependencies or execute repository code for extraction")
    return [f"{path}: {error}" for error in errors]


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


def resolve_container_security_checkout_ref(expression, event_name, github_ref, pr_head_sha=None):
    """Resolve protected-master schedule, exact PR head, and selected dispatch ref."""
    if not expression or not SCHEDULE_CHECKOUT_REF.fullmatch(expression):
        raise ValueError("unsupported container-security checkout ref expression")
    if event_name == "schedule":
        return "master"
    if event_name == "pull_request":
        if not pr_head_sha:
            raise ValueError("missing immutable pull-request head SHA")
        return pr_head_sha
    return github_ref


def resolve_restore_checkout_ref(expression, event_name, github_ref, github_sha):
    """Resolve the protected schedule, immutable PR candidate, and selected-ref policy."""
    if not expression or not RESTORE_CHECKOUT_REF.fullmatch(expression):
        raise ValueError("unsupported restore-rehearsal checkout ref expression")
    if event_name == "schedule":
        return "master"
    if event_name == "pull_request":
        return github_sha
    return github_ref


def validate_workflows(workflows_dir, require_codeql=False):
    violations = []
    if require_codeql and not (workflows_dir / "codeql.yml").is_file():
        violations.append(f"{workflows_dir}: required Java/Kotlin and Python CodeQL workflow is missing")
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
        if path.name == "codeql.yml":
            violations.extend(validate_codeql_workflow(path))
        if path.name == "ci.yml":
            contents = path.read_text(encoding="utf-8")
            if not re.search(r"(?m)^permissions:\s*$\n  contents: read\s*$", contents):
                violations.append(f"{path}: default workflow permissions must remain contents: read")
            build = BUILD_JOB.search(contents)
            if (not build or
                    'python scripts/validate-master-protection.py --repository "${{ github.repository }}"'
                    not in build.group("body")):
                violations.append(
                    f"{path}: required build job must validate the live protected-master policy"
                )
            dependency_review = DEPENDENCY_REVIEW_JOB.search(contents)
            dependency_review_requirements = (
                "if: github.event_name == 'pull_request'",
                "ref: ${{ github.event.pull_request.head.sha }}",
                "repository: ${{ github.event.pull_request.base.repo.full_name }}",
                "ref: ${{ github.event.pull_request.base.sha }}",
                "path: target/dependency-review-base",
                "persist-credentials: false",
                "BASE_REPOSITORY: ${{ github.event.pull_request.base.repo.full_name }}",
                "BASE_SHA: ${{ github.event.pull_request.base.sha }}",
                "HEAD_SHA: ${{ github.event.pull_request.head.sha }}",
                'test "$BASE_REPOSITORY" = "${{ github.repository }}"',
                "git -C target/dependency-review-base rev-parse HEAD",
                'test "$(git rev-parse HEAD)" = "$HEAD_SHA"',
                "actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294",
                "fail-on-severity: high",
                "fail-on-scopes: runtime, development, unknown",
                "license-check: false",
                "show-openssf-scorecard: false",
            )
            if not dependency_review or any(
                    item not in dependency_review.group("body")
                    for item in dependency_review_requirements):
                violations.append(
                    f"{path}: dependency review must compare the immutable same-repository PR base/head, "
                    "fail for HIGH/CRITICAL findings in every dependency scope, and leave license policy disabled"
                )
            checkout_ref = container_security_checkout_ref(path)
            if not checkout_ref or not SCHEDULE_CHECKOUT_REF.fullmatch(checkout_ref):
                violations.append(
                    f"{path}: container-security checkout must use master only for schedule "
                    "the immutable head SHA for pull_request, and github.ref for workflow_dispatch and push"
                )
            rehearsal_ref = restore_rehearsal_checkout_ref(path)
            if not rehearsal_ref or not RESTORE_CHECKOUT_REF.fullmatch(rehearsal_ref):
                violations.append(
                    f"{path}: restore-rehearsal checkout must use protected master for schedule "
                    "the immutable candidate SHA for pull requests, and github.ref for protected "
                    "push and explicit workflow_dispatch refs"
                )
            scope = RESTORE_PR_SCOPE_JOB.search(contents)
            scope_requirements = (
                "if: github.event_name == 'pull_request'",
                "run_restore: ${{ steps.scope.outputs.run_restore }}",
                "persist-credentials: false",
                "fetch-depth: 0",
                "ref: ${{ github.event.pull_request.head.sha }}",
                "BASE_SHA: ${{ github.event.pull_request.base.sha }}",
                "HEAD_SHA: ${{ github.event.pull_request.head.sha }}",
                "python scripts/restore_pr_scope.py",
                '--base-sha "$BASE_SHA"',
                '--head-sha "$HEAD_SHA"',
            )
            if not scope or any(item not in scope.group("body") for item in scope_requirements):
                violations.append(
                    f"{path}: restore PR scope detection must use the complete immutable base/head "
                    "history, disable persisted credentials, and publish the repository-owned decision"
                )
            rehearsal = RESTORE_REHEARSAL_JOB.search(contents)
            if (not rehearsal
                    or "needs: restore-pr-scope" not in rehearsal.group("body")
                    or "always()" not in rehearsal.group("body")
                    or "github.event_name == 'push'" not in rehearsal.group("body")
                    or "github.event_name == 'schedule'" not in rehearsal.group("body")
                    or "github.event_name == 'workflow_dispatch'" not in rehearsal.group("body")
                    or "github.event_name == 'pull_request'" not in rehearsal.group("body")
                    or "needs.restore-pr-scope.outputs.run_restore == 'true'" not in rehearsal.group("body")
                    or "run: ./scripts/restore-rehearsal.sh" not in rehearsal.group("body")
                    or "run: ./scripts/historical-forward-restore-rehearsal.sh" not in rehearsal.group("body")):
                violations.append(
                    f"{path}: restore-rehearsal must run for push, schedule, explicit dispatch, and "
                    "restore-relevant PRs, and invoke both repository-owned restore scripts"
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
            compatibility = MIGRATION_COMPATIBILITY.search(contents)
            compatibility_required = (
                "if: github.event_name == 'pull_request'",
                "BASE_REPOSITORY: ${{ github.event.pull_request.base.repo.full_name }}",
                "BASE_SHA: ${{ github.event.pull_request.base.sha }}",
                'test "$BASE_REPOSITORY" = "${{ github.repository }}"',
                "git -C target/openapi-baseline-source rev-parse HEAD",
                "python scripts/validate_migration_compatibility.py",
                "--base target/openapi-baseline-source",
                "--candidate .",
            )
            if not compatibility or any(
                    item not in compatibility.group("body") for item in compatibility_required):
                violations.append(
                    f"{path}: Flyway compatibility policy must fail closed against the exact "
                    "pull-request base repository and SHA"
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

    violations = validate_workflows(args.workflows_dir, require_codeql=True)
    if violations:
        for violation in violations:
            print(violation, file=sys.stderr)
        return 1
    print(f"GitHub Actions policy passed for {args.workflows_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
