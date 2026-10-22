#!/usr/bin/env bash
set -euo pipefail
builder_evidence="${PWD}/.tmp/container-security/builder"
mkdir -p "${builder_evidence}"
git rev-parse HEAD > "${builder_evidence}/source-sha.txt"
test "$(cat "${builder_evidence}/source-sha.txt")" = "${EXPECTED_SOURCE_SHA}"
for stage in builder app; do
  image="enterprise-shop/${stage}:ci"
  docker image inspect "${image}" > "${builder_evidence}/${stage}-image.json"
  test "$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "${image}")" = linux/amd64
  docker run --rm --platform linux/amd64 --entrypoint java "${image}" \
    -XshowSettings:properties -version > "${builder_evidence}/${stage}-java.txt" 2>&1
  grep -E '^[[:space:]]*java.specification.version = 21$' "${builder_evidence}/${stage}-java.txt"
  docker run --rm --platform linux/amd64 --entrypoint cat "${image}" /opt/java/openjdk/release > "${builder_evidence}/${stage}-jdk-release.txt"
  docker run --rm --platform linux/amd64 --entrypoint sha256sum "${image}" /opt/java/openjdk/lib/security/blocked.certs > "${builder_evidence}/${stage}-blocked.certs.sha256"
  test "$(cat "${builder_evidence}/${stage}-blocked.certs.sha256")" = '2596bb6dda83c66d96c5a252d7b2ae1f24cc1ae3f1890223b4f3591a8d52bf10  /opt/java/openjdk/lib/security/blocked.certs'
  docker run --rm --platform linux/amd64 --entrypoint /usr/local/bin/with-verified-blocklist "${image}" \
    /opt/java/openjdk/lib/security/blocked.certs /opt/java/openjdk/bin/java -version > "${builder_evidence}/${stage}-blocklist-guard-java.txt" 2>&1
  docker run --rm --platform linux/amd64 --entrypoint cat "${image}" /etc/os-release > "${builder_evidence}/${stage}-os-release.txt"
  docker run --rm --platform linux/amd64 --entrypoint dpkg-query "${image}" \
    -W '-f=${Package}\t${Version}\t${Architecture}\t${db:Status-Status}\n' > "${builder_evidence}/${stage}-dpkg.tsv"
  docker run --rm --platform linux/amd64 --entrypoint sh "${image}" \
    -c 'find /opt/java/openjdk/bin /opt/java/openjdk/lib /opt/java/openjdk/jmods -type f 2>/dev/null || test ! -d /opt/java/openjdk/jmods' \
    > "${builder_evidence}/${stage}-jdk-files.txt"
done
docker run --rm --platform linux/amd64 --entrypoint javac enterprise-shop/builder:ci -version > "${builder_evidence}/builder-javac.txt" 2>&1
grep -E '^javac 21\.' "${builder_evidence}/builder-javac.txt"
