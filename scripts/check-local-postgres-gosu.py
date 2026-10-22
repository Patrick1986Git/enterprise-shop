#!/usr/bin/env python3
"""Check local PostgreSQL gosu bytes without starting or changing Docker resources."""

import argparse
import gzip
import hashlib
import importlib.util
import io
import json
import re
import subprocess
import sys
import tarfile
import tempfile
import zlib
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("gosu_provenance", ROOT / "scripts/validate-gosu-provenance.py")
PROVENANCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROVENANCE)
TARGET = "usr/local/bin/gosu"
PARENTS = ("usr", "usr/local", "usr/local/bin")
WHITEOUTS = {".wh.usr", "usr/.wh.local", "usr/local/.wh.bin", "usr/local/bin/.wh.gosu"}
WHITEOUTS |= {str(PurePosixPath(parent) / ".wh..wh..opq") for parent in ("", *PARENTS)}
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def docker(*args, root=ROOT):
    try:
        return subprocess.run(["docker", *args], cwd=root, check=True, stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, timeout=120).stdout
    except FileNotFoundError:
        raise ValueError("Docker is unavailable; install Docker and the Compose plugin") from None
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        raise ValueError("Docker read-only inspection failed; check daemon access and Compose configuration") from None


def load_candidate(root=ROOT):
    candidate = json.loads(PROVENANCE.regular_bytes(root / ".github/security/gosu/candidate.json"))
    require(isinstance(candidate, dict), "Malformed reviewed gosu receipt")
    try:
        PROVENANCE.validate_candidate(candidate)
    except AttributeError:
        raise ValueError("Malformed reviewed gosu receipt") from None
    return candidate


def validate_payload(payload, mode, candidate):
    require(mode & 0o111, "Installed gosu is not executable")
    require(hashlib.sha256(payload).hexdigest() == candidate["binary"]["sha256"],
            "Installed gosu differs from the reviewed patched executable; operator image adoption is required")


def member_name(name):
    path = PurePosixPath(name)
    require(not path.is_absolute() and ".." not in path.parts, "Unsafe Docker archive path")
    return str(path)


def regular_member(archive, name):
    matches = [member for member in archive.getmembers() if member.name == name]
    require(len(matches) == 1 and matches[0].isfile(), "Missing or ambiguous Docker archive evidence")
    return archive.extractfile(matches[0]).read()


def container_payload(archive_bytes):
    with tarfile.open(fileobj=io.BytesIO(archive_bytes)) as archive:
        members = archive.getmembers()
        require(len(members) == 1 and members[0].isfile()
                and member_name(members[0].name) == "gosu",
                "Installed gosu must be one regular file, without links")
        return archive.extractfile(members[0]).read(), members[0].mode


def image_payload(archive_path, image_id):
    """Read only the effective gosu path; never extract an image filesystem."""
    payload, mode = None, 0
    with tarfile.open(archive_path) as saved:
        manifest = json.loads(regular_member(saved, "manifest.json"))
        require(isinstance(manifest, list) and len(manifest) == 1, "Ambiguous saved PostgreSQL image")
        entry = manifest[0]
        config_bytes = regular_member(saved, entry["Config"])
        require("sha256:" + hashlib.sha256(config_bytes).hexdigest() == image_id,
                "Saved PostgreSQL image identity changed during inspection")
        config = json.loads(config_bytes)
        require(config["os"] + "/" + config["architecture"] == "linux/amd64",
                "PostgreSQL image architecture is unsupported; the reviewed candidate requires linux/amd64")
        layers, digests = entry["Layers"], config["rootfs"]["diff_ids"]
        require(isinstance(layers, list) and layers and len(layers) == len(digests),
                "Missing PostgreSQL image layer evidence")
        for name, digest in zip(layers, digests):
            layer_bytes = regular_member(saved, name)
            if layer_bytes.startswith(b"\x1f\x8b"):
                layer_bytes = gzip.decompress(layer_bytes)
            require("sha256:" + hashlib.sha256(layer_bytes).hexdigest() == digest,
                    "Saved PostgreSQL image layer digest mismatch")
            with tarfile.open(fileobj=io.BytesIO(layer_bytes)) as layer:
                relevant = {}
                for member in layer:
                    path = member_name(member.name)
                    if path in {TARGET, *PARENTS, *WHITEOUTS}:
                        require(path not in relevant, "Ambiguous gosu path in PostgreSQL image layer")
                        relevant[path] = member
                for path in PARENTS:
                    require(path not in relevant or relevant[path].isdir(),
                            "Linked or unexpected gosu ancestor in PostgreSQL image")
                for path in WHITEOUTS & relevant.keys():
                    require((relevant[path].isfile() or relevant[path].ischr()) and relevant[path].size == 0,
                            "Malformed PostgreSQL image whiteout")
                    payload, mode = None, 0
                if TARGET in relevant:
                    member = relevant[TARGET]
                    require(member.isfile(), "PostgreSQL image gosu must be a regular file, without links")
                    payload, mode = layer.extractfile(member).read(), member.mode
    require(payload is not None, "PostgreSQL image has no readable installed gosu executable")
    return payload, mode


def verify_image(image_id, candidate, root=ROOT):
    require(IMAGE_ID.fullmatch(image_id) is not None, "Missing immutable PostgreSQL image identity")
    platform = docker("image", "inspect", "--format", "{{.Os}}/{{.Architecture}}", image_id, root=root).decode().strip()
    require(platform == candidate["binary"]["platform"],
            "PostgreSQL image architecture is unsupported; the reviewed candidate requires linux/amd64")
    with tempfile.TemporaryDirectory(prefix="enterprise-shop-gosu-") as directory:
        archive = Path(directory) / "image.tar"
        docker("image", "save", "--output", str(archive), image_id, root=root)
        validate_payload(*image_payload(archive, image_id), candidate)


def verify_build_inputs(root, candidate):
    for name, digest in candidate["lockfiles"].items():
        require(hashlib.sha256(PROVENANCE.regular_bytes(root / "docker/postgres/gosu" / name)).hexdigest() == digest,
                "Reviewed gosu build lockfiles are missing or changed")
    dockerfile = PROVENANCE.regular_bytes(root / "docker/postgres/Dockerfile").decode()
    require("FROM " + candidate["builder"]["image"] + " AS gosu-builder" in dockerfile.splitlines()
            and "ADD --checksum=sha256:" + candidate["source"]["archive_sha256"] + " "
                + candidate["source"]["archive_url"] + " /tmp/gosu-source.tar.gz" in dockerfile.splitlines()
            and 'test "$(go env GOVERSION)" = ' + candidate["builder"]["go_version"] in dockerfile
            and dockerfile.count("go build " + candidate["build_flags"] + " -o /out/gosu") == 2
            and "cmp /out/gosu /out/gosu.repeat" in dockerfile
            and "'" + candidate["binary"]["sha256"] + "  /out/gosu'" in dockerfile
            and "sha256sum -c /out/gosu.sha256" in dockerfile
            and "COPY --from=gosu-builder --chmod=0755 /out/gosu /usr/local/bin/gosu" in dockerfile,
            "PostgreSQL Dockerfile is missing reviewed gosu source, builder or executable controls")


def verify_local_postgres(root=ROOT):
    candidate = load_candidate(root)
    containers = docker("compose", "ps", "--all", "--quiet", "postgres", root=root).decode().split()
    require(len(containers) <= 1, "Multiple local PostgreSQL containers require operator review")
    if containers:
        container = containers[0]
        image_id = docker("inspect", "--format", "{{.Image}}", container, root=root).decode().strip()
        require(IMAGE_ID.fullmatch(image_id) is not None, "Missing immutable PostgreSQL image identity")
        platform = docker("image", "inspect", "--format", "{{.Os}}/{{.Architecture}}", image_id, root=root).decode().strip()
        require(platform == candidate["binary"]["platform"],
                "PostgreSQL image architecture is unsupported; the reviewed candidate requires linux/amd64")
        payload, mode = container_payload(docker("cp", container + ":/" + TARGET, "-", root=root))
        validate_payload(payload, mode, candidate)
        return "existing"

    volumes = docker("volume", "ls", "--filter", "name=^enterprise_shop_postgres18_volume$", "--quiet", root=root).decode().split()
    require(not volumes, "An existing PostgreSQL 18 volume has no container; operator review is required before attachment")
    images = docker("compose", "config", "--images", "postgres", root=root).decode().split()
    require(len(images) == 1, "Cannot determine one local PostgreSQL image")
    cached = set(docker("image", "ls", "--quiet", "--no-trunc", images[0], root=root).decode().split())
    require(len(cached) <= 1, "Ambiguous cached PostgreSQL image")
    if cached:
        verify_image(cached.pop(), candidate, root)
    else:
        platform = docker("info", "--format", "{{.OSType}}/{{.Architecture}}", root=root).decode().strip()
        require(platform in {"linux/amd64", "linux/x86_64"},
                "Docker architecture is unsupported; the reviewed candidate requires linux/amd64")
        verify_build_inputs(root, candidate)
    return "first-start"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", help="inspect a manually built image without starting a container")
    args = parser.parse_args()
    try:
        if args.image:
            candidate = load_candidate()
            image_id = docker("image", "inspect", "--format", "{{.Id}}", args.image).decode().strip()
            verify_image(image_id, candidate)
            print("Reviewed PostgreSQL gosu identity verified")
        else:
            print(verify_local_postgres())
    except (ValueError, KeyError, TypeError, OSError, EOFError, tarfile.TarError, zlib.error) as error:
        print("PostgreSQL gosu preflight failed: " + str(error)
              + ". No Docker resources were changed. See docs/operations/local-development.md for operator image adoption.",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
