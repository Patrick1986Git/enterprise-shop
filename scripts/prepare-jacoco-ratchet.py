#!/usr/bin/env python3
"""Prepare a deterministic JaCoCo baseline proposal from trusted CI evidence."""

import argparse
import importlib.util
import json
import sys
from pathlib import Path


VALIDATOR_PATH = Path(__file__).with_name("validate-jacoco-report.py")
SPEC = importlib.util.spec_from_file_location("jacoco_validator", VALIDATOR_PATH)
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)


class ProposalError(ValueError):
    """Raised when candidate evidence cannot safely produce a proposal."""


def compare(candidate, baseline):
    left = candidate.covered * baseline.total
    right = baseline.covered * candidate.total
    return left, right, (left > right) - (left < right)


def ratchet_decision(lines, branches, baselines):
    comparisons = {
        "LINE": compare(lines, baselines["LINE"]),
        "BRANCH": compare(branches, baselines["BRANCH"]),
    }
    if any(result[2] < 0 for result in comparisons.values()):
        regressed = ", ".join(name for name, result in comparisons.items() if result[2] < 0)
        raise ProposalError(f"candidate coverage regresses for: {regressed}")
    return any(result[2] > 0 for result in comparisons.values()), comparisons


def validate_provenance(value, expected_sha):
    if not isinstance(value, dict) or set(value) != {"pull_requests", "workflow", "artifact"}:
        raise ProposalError("provenance must contain only pull_requests, workflow, and artifact")
    pulls = value["pull_requests"]
    if not isinstance(pulls, list):
        raise ProposalError("provenance pull_requests must be a list")
    matching = [
        pull for pull in pulls
        if isinstance(pull, dict)
        and pull.get("merged_at")
        and pull.get("merge_commit_sha") == expected_sha
        and isinstance(pull.get("number"), int)
        and not isinstance(pull.get("number"), bool)
        and pull["number"] > 0
    ]
    if len(matching) != 1 or len(pulls) != 1:
        raise ProposalError("exactly one merged pull request must identify the master squash SHA")
    workflow = value["workflow"]
    required_workflow = {"name", "run_number", "run_id", "head_sha", "event", "conclusion"}
    if not isinstance(workflow, dict) or set(workflow) != required_workflow:
        raise ProposalError("workflow provenance has an invalid shape")
    if workflow["name"] != "CI" or workflow["event"] != "push" or workflow["conclusion"] != "success":
        raise ProposalError("candidate must come from a successful CI push run")
    if workflow["head_sha"] != expected_sha:
        raise ProposalError("workflow head SHA does not match the candidate master SHA")
    for field in ("run_number", "run_id"):
        if isinstance(workflow[field], bool) or not isinstance(workflow[field], int) or workflow[field] <= 0:
            raise ProposalError(f"workflow {field} must be a positive integer")
    artifact = value["artifact"]
    required_artifact = {"id", "name", "digest"}
    if not isinstance(artifact, dict) or set(artifact) != required_artifact:
        raise ProposalError("artifact provenance has an invalid shape")
    if artifact["name"] != "jacoco-coverage-report" or not isinstance(artifact["id"], int) or artifact["id"] <= 0:
        raise ProposalError("artifact provenance does not identify the JaCoCo artifact")
    digest = artifact["digest"]
    if not isinstance(digest, str) or not digest.startswith("sha256:") or len(digest) != 71:
        raise ProposalError("artifact digest must be a GitHub SHA-256 digest")
    return matching[0]["number"], workflow, artifact


def render_policy(pull_number, workflow, sha, lines, branches):
    return {
        "schema_version": 1,
        "source": {
            "pull_request": pull_number,
            "workflow": workflow["name"],
            "run_number": workflow["run_number"],
            "head_sha": sha,
        },
        "metrics": {
            "LINE": {"covered": lines.covered, "missed": lines.missed},
            "BRANCH": {"covered": branches.covered, "missed": branches.missed},
        },
    }


def render_body(sha, pull_number, workflow, artifact, baselines, candidates, comparisons):
    rows = []
    for metric in ("LINE", "BRANCH"):
        old, new = baselines[metric], candidates[metric]
        left, right, _ = comparisons[metric]
        rows.append(
            f"| {metric} | {old.covered} / {old.missed} | {new.covered} / {new.missed} | "
            f"`{new.covered} * {old.total} = {left} >= {old.covered} * {new.total} = {right}` |"
        )
    return "\n".join((
        "## Automated JaCoCo baseline ratchet", "",
        "This proposal was deterministically generated from successful post-merge `master` CI. "
        "It is not approved or merged automatically.", "",
        f"- Source master SHA: `{sha}`", f"- Source pull request: #{pull_number}",
        f"- CI run: #{workflow['run_number']} / ID `{workflow['run_id']}`",
        f"- Artifact: ID `{artifact['id']}`, digest `{artifact['digest']}`", "",
        "| Metric | Previous covered / missed | Candidate covered / missed | Exact proof |",
        "| --- | ---: | ---: | --- |", *rows, "",
        "Exactly one file changes: `.github/coverage/jacoco-baseline.json`.", "",
        "Normal CI and CodeQL must succeed on this PR before maintainer review and squash merge."
    )) + "\n"


def prepare(report, policy_path, provenance_path, expected_sha, output_body):
    VALIDATOR.validate_companion_reports(report.parent)
    lines, branches = VALIDATOR.parse_report(report)
    baselines = VALIDATOR.parse_policy(policy_path)
    provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
    pull_number, workflow, artifact = validate_provenance(provenance, expected_sha)
    should_ratchet, comparisons = ratchet_decision(lines, branches, baselines)
    if not should_ratchet:
        return False
    policy = render_policy(pull_number, workflow, expected_sha, lines, branches)
    policy_path.write_text(json.dumps(policy, indent=2) + "\n", encoding="utf-8")
    output_body.write_text(render_body(
        expected_sha, pull_number, workflow, artifact, baselines,
        {"LINE": lines, "BRANCH": branches}, comparisons
    ), encoding="utf-8")
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--provenance", type=Path, required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--output-body", type=Path, required=True)
    args = parser.parse_args()
    try:
        changed = prepare(args.report, args.policy, args.provenance, args.expected_sha, args.output_body)
    except (OSError, json.JSONDecodeError, VALIDATOR.ReportValidationError,
            VALIDATOR.PolicyValidationError, ProposalError) as error:
        print(f"JaCoCo ratchet preparation failed: {error}", file=sys.stderr)
        return 1
    print("ratchet=true" if changed else "ratchet=false")
    return 0


if __name__ == "__main__":
    sys.exit(main())
