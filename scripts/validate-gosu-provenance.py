#!/usr/bin/env python3
"""Bind PostgreSQL's installed gosu to the reviewed rebuild and patched source."""

import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[1]
REVIEWED = ROOT / "docker/postgres/gosu"
CANDIDATE = ROOT / ".github/security/gosu/candidate.json"
LOCKFILES = {"go.mod", "go.sum"}
REQUIRED_BUILD = {
    "-buildmode": "exe", "-compiler": "gc", "-trimpath": "true",
    "CGO_ENABLED": "0", "GOOS": "linux", "GOARCH": "amd64", "GOAMD64": "v1",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def regular_bytes(path):
    require(path.is_file() and not path.is_symlink(), f"Expected regular file: {path}")
    return path.read_bytes()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def validate_candidate(candidate):
    require(type(candidate.get("schema_version")) is int and candidate["schema_version"] == 1,
            "Unsupported gosu receipt schema")
    source = candidate["source"]
    require(source["repository"] == "https://github.com/tianon/gosu", "Wrong gosu source repository")
    require(re.fullmatch(r"[0-9a-f]{40}", source["commit"]) is not None, "Invalid gosu source commit")
    require(source["archive_url"] == "https://codeload.github.com/tianon/gosu/tar.gz/" + source["commit"],
            "Source archive does not identify the reviewed commit")
    require(re.fullmatch(r"[0-9a-f]{64}", source["archive_sha256"]) is not None,
            "Invalid gosu source archive digest")
    builder = candidate["builder"]
    require(re.fullmatch(r"go[0-9]+\.[0-9]+\.[0-9]+", builder["go_version"]) is not None,
            "Invalid gosu compiler version")
    require(re.fullmatch(r"docker\.io/library/golang:[^@\s]+@sha256:[0-9a-f]{64}", builder["image"]) is not None,
            "Gosu builder must have an immutable official image identity")
    require(set(candidate["lockfiles"]) == LOCKFILES, "Expected exactly the two reviewed Go lockfiles")
    require(all(re.fullmatch(r"[0-9a-f]{64}", value) for value in candidate["lockfiles"].values()),
            "Invalid gosu lockfile digest")
    binary = candidate["binary"]
    require(re.fullmatch(r"[0-9a-f]{64}", binary["sha256"]) is not None, "Invalid gosu executable digest")
    require(binary["platform"] == "linux/amd64", "Unsupported gosu platform")
    require(binary["path"] == binary["main_module"] == "github.com/tianon/gosu"
            and binary["main_version"] == "(devel)", "Rebuild must retain its actual gosu module identity")
    require(all(binary["build"].get(key) == value for key, value in REQUIRED_BUILD.items()),
            "Missing required gosu build settings")
    require(set(binary["build"]) <= set(REQUIRED_BUILD) | {"DefaultGODEBUG"},
            "Unexpected reviewed gosu build settings")
    require(set(binary["dependencies"]) == {"github.com/moby/sys/user", "golang.org/x/sys"},
            "Unexpected reviewed gosu dependency graph")
    require(binary["dependencies"]["github.com/moby/sys/user"]["version"] == "v0.4.1",
            "Expected the reviewed patched user parser dependency")
    for module in binary["dependencies"].values():
        require(set(module) == {"version", "sum"}
                and re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", module["version"]) is not None
                and re.fullmatch(r"h1:[A-Za-z0-9+/]{43}=", module["sum"]) is not None,
                "Invalid reviewed gosu dependency identity")


def parse_buildinfo(text):
    lines = text.splitlines()
    require(bool(lines), "Missing installed gosu build information")
    header = re.fullmatch(r"/evidence/gosu: (go[0-9]+\.[0-9]+\.[0-9]+)", lines[0])
    require(header is not None, "Invalid installed gosu build information header")
    result = {"go_version": header.group(1), "dependencies": {}, "build": {}}
    for line in lines[1:]:
        require(line.startswith("\t"), "Malformed installed gosu build information")
        fields = line[1:].split("\t")
        kind = fields[0]
        if kind == "path":
            require(len(fields) == 2 and "path" not in result, "Duplicate or malformed gosu path")
            result["path"] = fields[1]
        elif kind == "mod":
            require(len(fields) in (3, 4) and "main_module" not in result
                    and (len(fields) == 3 or fields[3] == ""), "Unexpected gosu main module checksum or replacement")
            result.update(main_module=fields[1], main_version=fields[2])
        elif kind == "dep":
            require(len(fields) == 4 and fields[1] not in result["dependencies"],
                    "Duplicate or malformed gosu dependency")
            result["dependencies"][fields[1]] = {"version": fields[2], "sum": fields[3]}
        elif kind == "build":
            require(len(fields) == 2 and "=" in fields[1], "Malformed gosu build setting")
            key, value = fields[1].split("=", 1)
            require(key not in result["build"], "Duplicate gosu build setting")
            result["build"][key] = value
        else:
            raise ValueError("Unexpected gosu build information record, including module replacements")
    return result


def validate_binary(candidate, payload, buildinfo):
    expected = candidate["binary"]
    require(sha256(payload) == expected["sha256"], "Installed gosu executable digest mismatch")
    require(len(payload) >= 64 and payload[:7] == b"\x7fELF\x02\x01\x01",
            "Installed gosu is not ELF64 little endian")
    require(struct.unpack_from("<HHI", payload, 16) == (2, 62, 1),
            "Installed gosu is not a Linux/amd64 executable")
    actual = parse_buildinfo(buildinfo)
    require(actual["go_version"] == candidate["builder"]["go_version"], "Installed gosu compiler mismatch")
    require(all(actual.get(key) == expected[key] for key in ("path", "main_module", "main_version")),
            "Installed gosu module identity mismatch")
    require(actual["dependencies"] == expected["dependencies"], "Installed gosu dependency identities mismatch")
    require(all(actual["build"].get(key) == value for key, value in expected["build"].items()),
            "Installed gosu build settings mismatch")
    require(set(actual["build"]) <= set(expected["build"]) | {"DefaultGODEBUG"},
            "Unexpected installed gosu build settings or VCS claims")


def git(source, *args):
    return subprocess.run(["git", "--no-replace-objects", "-C", str(source), *args], check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def validate_source(candidate, source, reviewed=REVIEWED):
    require(source.is_dir() and not source.is_symlink(), "Missing gosu source checkout")
    require(git(source, "rev-parse", "HEAD").decode().strip() == candidate["source"]["commit"],
            "Gosu source checkout is not the reviewed immutable commit")
    require(not git(source, "ls-files", "--others", "-z"), "Untracked gosu source files are forbidden")
    records = git(source, "ls-tree", "-rz", "--full-tree", "HEAD").split(b"\0")
    tracked = set()
    for record in records:
        if not record:
            continue
        identity, name = record.split(b"\t", 1)
        mode, kind, object_id = identity.decode().split(" ")
        name = name.decode()
        relative = PurePosixPath(name)
        require(not relative.is_absolute() and ".." not in relative.parts
                and kind == "blob" and mode in {"100644", "100755", "120000"},
                "Unsupported gosu source tree entry")
        tracked.add(name)
        path = source / name
        if name in LOCKFILES:
            continue
        original = git(source, "cat-file", "blob", object_id)
        if mode == "120000":
            require(path.is_symlink() and os.fsencode(os.readlink(path)) == original,
                    f"Gosu source symlink changed: {name}")
        else:
            require(regular_bytes(path) == original, f"Gosu source bytes changed: {name}")
            require(bool(path.stat().st_mode & 0o111) == (mode == "100755"),
                    f"Gosu source executable mode changed: {name}")
    require(LOCKFILES <= tracked, "Gosu source checkout is missing tracked lockfiles")
    for name, digest in candidate["lockfiles"].items():
        require(sha256(regular_bytes(reviewed / name)) == digest, f"Reviewed gosu lockfile digest mismatch: {name}")
        require(sha256(regular_bytes(source / name)) == digest, f"Analyzed gosu lockfile digest mismatch: {name}")


def validate(candidate, evidence, source, reviewed=REVIEWED):
    validate_candidate(candidate)
    validate_binary(candidate, regular_bytes(evidence / "gosu"),
                    regular_bytes(evidence / "buildinfo.txt").decode("utf-8"))
    validate_source(candidate, source, reviewed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, default=Path(".tmp/container-security/gosu"))
    parser.add_argument("--source", type=Path, default=Path(".tmp/gosu-source"))
    args = parser.parse_args()
    try:
        candidate = json.loads(regular_bytes(CANDIDATE))
        validate(candidate, args.evidence, args.source)
    except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError) as error:
        print(f"Gosu provenance validation failed: {error}", file=sys.stderr)
        return 1
    print("Installed gosu executable, compiler, dependencies and patched source provenance verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
