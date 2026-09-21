#!/usr/bin/env python3
"""Fail-closed review boundary for Flyway changes in pull requests."""

import argparse
import json
import re
import sys
from pathlib import Path


MIGRATIONS = Path("src/main/resources/db/migration")
MANIFEST = Path(".github/database-migration-compatibility.json")
NAME = re.compile(r"^(V[1-9][0-9]*)__.+\.sql$")
DECISIONS = {"rolling-safe", "expand-contract", "coordinated-maintenance", "owner-review"}


def migration_files(root):
    directory = root / MIGRATIONS
    return {path.name: path.read_bytes() for path in directory.glob("V*__*.sql")}


def validate(base, candidate):
    errors = []
    base_files = migration_files(base)
    candidate_files = migration_files(candidate)
    for name, contents in base_files.items():
        if name not in candidate_files:
            errors.append(f"historical migration deleted: {name}")
        elif candidate_files[name] != contents:
            errors.append(f"historical migration changed: {name}")

    new_files = sorted(set(candidate_files) - set(base_files))
    try:
        manifest = json.loads((candidate / MANIFEST).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"cannot read compatibility manifest: {error}"]
    decisions = manifest.get("migrations", {})
    try:
        base_manifest = json.loads((base / MANIFEST).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        base_manifest = {}
    base_decisions = base_manifest.get("migrations", {})
    for name in new_files:
        match = NAME.fullmatch(name)
        if not match:
            errors.append(f"invalid versioned migration filename: {name}")
            continue
        version = match.group(1)
        decision = decisions.get(version)
        if not isinstance(decision, dict):
            errors.append(f"{name} has no migrations.{version} compatibility decision")
            continue
        if base_decisions.get(version) == decision:
            errors.append(f"{name} must add or update its migrations.{version} decision")
        classification = decision.get("classification")
        if classification not in DECISIONS:
            errors.append(f"{name} has invalid classification {classification!r}")
        for field in ("rationale", "previousRevisionEvidence", "ownerReview"):
            if not isinstance(decision.get(field), str) or not decision[field].strip():
                errors.append(f"{name} requires a non-empty {field}")

    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--candidate", default=Path("."), type=Path)
    args = parser.parse_args()
    errors = validate(args.base.resolve(), args.candidate.resolve())
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("Flyway migration compatibility decision boundary passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
