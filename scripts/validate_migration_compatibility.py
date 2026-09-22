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
DECISION_FIELDS = {"classification", "rationale", "previousRevisionEvidence", "ownerReview"}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def migration_inventory(root):
    directory = root / MIGRATIONS
    files = {}
    versions = {}
    errors = []
    for path in sorted(directory.glob("V*__*.sql")):
        match = NAME.fullmatch(path.name)
        if not match:
            errors.append(f"invalid versioned migration filename: {path.name}")
            continue
        version = match.group(1)
        if version in versions:
            errors.append(
                f"ambiguous migration version {version}: {versions[version]} and {path.name}"
            )
        else:
            versions[version] = path.name
        files[path.name] = path.read_bytes()
    return files, versions, errors


def manifest_decisions(root, required):
    path = root / MANIFEST
    if not path.exists() and not required:
        return {}, []
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        return {}, [f"cannot read compatibility manifest: {error}"]
    errors = []
    if not isinstance(manifest, dict):
        return {}, ["compatibility manifest must be a JSON object"]
    unexpected_top_level = set(manifest) - {"schemaVersion", "migrations"}
    if unexpected_top_level:
        errors.append(
            f"compatibility manifest has unsupported fields: {sorted(unexpected_top_level)}"
        )
    if manifest.get("schemaVersion") != 1 or isinstance(manifest.get("schemaVersion"), bool):
        errors.append("compatibility manifest schemaVersion must be integer 1")
    migrations = manifest.get("migrations")
    if not isinstance(migrations, dict):
        errors.append("compatibility manifest migrations must be an object")
        return {}, errors
    for version, decision in migrations.items():
        if not isinstance(version, str) or not re.fullmatch(r"V[1-9][0-9]*", version):
            errors.append(f"invalid migration decision key: {version!r}")
            continue
        if not isinstance(decision, dict):
            errors.append(f"migrations.{version} must be an object")
            continue
        unexpected = set(decision) - DECISION_FIELDS
        missing = DECISION_FIELDS - set(decision)
        if unexpected:
            errors.append(f"migrations.{version} has unsupported fields: {sorted(unexpected)}")
        if missing:
            errors.append(f"migrations.{version} is missing fields: {sorted(missing)}")
        classification = decision.get("classification")
        if classification not in DECISIONS:
            errors.append(f"migrations.{version} has invalid classification {classification!r}")
        for field in DECISION_FIELDS - {"classification"}:
            if not isinstance(decision.get(field), str) or not decision[field].strip():
                errors.append(f"migrations.{version} requires a non-empty {field}")
    return migrations, errors


def validate(base, candidate):
    base_files, _, errors = migration_inventory(base)
    candidate_files, candidate_versions, candidate_inventory_errors = migration_inventory(candidate)
    errors.extend(candidate_inventory_errors)
    for name, contents in base_files.items():
        if name not in candidate_files:
            errors.append(f"historical migration deleted: {name}")
        elif candidate_files[name] != contents:
            errors.append(f"historical migration changed: {name}")

    new_files = sorted(set(candidate_files) - set(base_files))
    decisions, manifest_errors = manifest_decisions(candidate, required=True)
    base_decisions, base_manifest_errors = manifest_decisions(base, required=False)
    errors.extend(manifest_errors)
    errors.extend(f"protected base {error}" for error in base_manifest_errors)
    for version, base_decision in base_decisions.items():
        if version not in decisions:
            errors.append(f"historical compatibility decision deleted: {version}")
        elif decisions[version] != base_decision:
            errors.append(f"historical compatibility decision changed: {version}")
    for version in decisions:
        if version not in candidate_versions:
            errors.append(f"migration decision has no matching migration file: {version}")
    for name in new_files:
        match = NAME.fullmatch(name)
        version = match.group(1)
        decision = decisions.get(version)
        if not isinstance(decision, dict):
            errors.append(f"{name} has no migrations.{version} compatibility decision")
            continue
        if base_decisions.get(version) == decision:
            errors.append(f"{name} must add or update its migrations.{version} decision")

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
