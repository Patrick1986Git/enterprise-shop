#!/usr/bin/env python3
"""Validate the repository-pinned Maven distribution integrity policy."""

import re
import sys
from pathlib import Path
from urllib.parse import urlparse


PROPERTIES = Path(".mvn/wrapper/maven-wrapper.properties")
DOCKERFILE = Path("Dockerfile")
EXPECTED_ARCHIVE = "apache-maven-3.9.16-bin.zip"
EXPECTED_SHA256 = "5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce"
SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
BUILDER_UNZIP_INSTALL = """RUN apt-get update \\
    && apt-get install --no-install-recommends -y unzip \\
    && rm -rf /var/lib/apt/lists/*"""


def read_properties(path):
    properties = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise ValueError(f"{path}: malformed property line: {line}")
        properties[key.strip()] = value.strip()
    return properties


def validate(path=PROPERTIES):
    try:
        properties = read_properties(path)
    except (OSError, ValueError) as error:
        return [str(error)]

    violations = []
    distribution_url = properties.get("distributionUrl", "")
    checksum = properties.get("distributionSha256Sum", "")

    if urlparse(distribution_url).scheme != "https":
        violations.append("distributionUrl must use HTTPS")
    archive = urlparse(distribution_url).path.rsplit("/", 1)[-1]
    if archive != EXPECTED_ARCHIVE:
        violations.append(
            f"distributionUrl must select the reviewed archive {EXPECTED_ARCHIVE}"
        )
    if not SHA256.fullmatch(checksum):
        violations.append(
            "distributionSha256Sum must contain exactly 64 hexadecimal characters"
        )
    elif checksum.lower() != EXPECTED_SHA256:
        violations.append(
            f"distributionSha256Sum must match the reviewed digest for {EXPECTED_ARCHIVE}"
        )
    return violations


def validate_dockerfile(path=DOCKERFILE):
    try:
        contents = path.read_text(encoding="utf-8")
    except OSError as error:
        return [str(error)]

    first_wrapper_invocation = contents.find("./mvnw")
    unzip_install = contents.find(BUILDER_UNZIP_INSTALL)
    if unzip_install == -1 or not 0 <= unzip_install < first_wrapper_invocation:
        return [
            "Dockerfile builder must install unzip without recommended packages before "
            "its first ./mvnw invocation"
        ]
    return []


def main():
    violations = validate() + validate_dockerfile()
    if violations:
        print("Maven Wrapper integrity policy violations:", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1
    print(f"Maven Wrapper integrity policy is valid for {EXPECTED_ARCHIVE}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
