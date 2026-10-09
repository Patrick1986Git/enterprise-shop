#!/usr/bin/env python3
"""Bind CodeQL's extracted source archive to every tracked Python source byte."""

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlparse


def validate_coverage(root, inventory, archive, sarif, head_sha, extracted_files):
    if not re.fullmatch(r"[0-9a-f]{40}", head_sha):
        raise ValueError("Missing exact analyzed HEAD")
    if not inventory or not inventory.endswith(b"\0"):
        raise ValueError("Missing NUL-delimited tracked Python inventory")
    paths = inventory[:-1].decode("utf-8").split("\0")
    if len(paths) != len(set(paths)):
        raise ValueError("Duplicate tracked Python source")
    root = root.resolve()
    tuples = extracted_files.get("#select", {}).get("tuples")
    if not isinstance(tuples, list) or not tuples:
        raise ValueError("Missing CodeQL extracted-file query results")
    analyzed = set()
    for row in tuples:
        if not isinstance(row, list) or len(row) != 2 or not isinstance(row[0], dict):
            raise ValueError("Malformed CodeQL extracted-file query result")
        url = urlparse(row[0].get("url", {}).get("uri", ""))
        if url.scheme != "file" or url.netloc:
            raise ValueError("Missing extracted-file source identity")
        analyzed.add(unquote(url.path))
    prefix = root.as_posix().lstrip("/") + "/"
    sources = []
    with zipfile.ZipFile(archive) as extracted:
        names = extracted.namelist()
        for relative in sorted(paths):
            path = PurePosixPath(relative)
            if path.is_absolute() or ".." in path.parts or path.suffix != ".py":
                raise ValueError(f"Invalid tracked Python source: {relative}")
            source = root / relative
            if source.as_posix() not in analyzed:
                raise ValueError(f"Python source not present in extracted-file query: {relative}")
            if source.is_symlink() or not source.is_file() or not source.resolve().is_relative_to(root):
                raise ValueError(f"Missing or linked tracked Python source: {relative}")
            matches = [name for name in names if name.lstrip("/") == prefix + relative]
            if len(matches) != 1:
                raise ValueError(f"Missing or ambiguous extracted Python source: {relative}")
            data = source.read_bytes()
            if extracted.read(matches[0]) != data:
                raise ValueError(f"Extracted Python source bytes differ: {relative}")
            sources.append({"path": relative, "sha256": hashlib.sha256(data).hexdigest()})
    runs = sarif.get("runs", [])
    if sarif.get("version") != "2.1.0" or len(runs) != 1:
        raise ValueError("Missing Python SARIF analysis")
    run = runs[0]
    tool = run.get("tool", {})
    rules = [rule for component in [tool.get("driver", {}), *tool.get("extensions", [])]
             for rule in component.get("rules", [])]
    if tool.get("driver", {}).get("name") != "CodeQL" or not any(
            rule.get("id", "").startswith("py/") for rule in rules):
        raise ValueError("Missing Python CodeQL security queries")
    invocations = run.get("invocations", [])
    if not invocations or any(item.get("executionSuccessful") is not True for item in invocations):
        raise ValueError("Python CodeQL query execution was unsuccessful")
    results = run.get("results")
    if not isinstance(results, list):
        raise ValueError("Missing Python CodeQL findings evidence")
    return {"head_sha": head_sha, "tracked_python_files": len(sources),
            "extracted_python_files": len(sources),
            "security_query_rules": len(rules), "findings": len(results),
            "source_archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "sources": sources}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--databases", required=True, help="CodeQL analyze db-locations JSON")
    parser.add_argument("--sarif-directory", required=True, type=Path)
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--extracted", required=True, type=Path)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        databases = json.loads(args.databases)
        if set(databases) != {"python"}:
            raise ValueError("Expected exactly one Python CodeQL database")
        report = validate_coverage(Path.cwd(), args.inventory.read_bytes(),
                                   Path(databases["python"]) / "src.zip",
                                   json.loads((args.sarif_directory / "python.sarif").read_text()),
                                   args.head_sha, json.loads(args.extracted.read_text()))
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print(f"Python CodeQL coverage evidence failed: {error}", file=sys.stderr)
        return 1
    print(f"Verified {report['extracted_python_files']}/{report['tracked_python_files']} "
          f"tracked Python files against CodeQL source bytes at {report['head_sha']}; "
          f"{report['security_query_rules']} rules, {report['findings']} findings")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
