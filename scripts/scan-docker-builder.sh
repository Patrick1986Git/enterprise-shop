#!/usr/bin/env bash
# Preserve both scanner outcomes; the policy gate runs after evidence upload.
set -euo pipefail
builder_evidence="${PWD}/.tmp/container-security/builder"
mkdir -p "${builder_evidence}" .tmp/container-security/trivy-cache
rm -f "${builder_evidence}/policy-result.json" "${builder_evidence}/trivy-raw.json" "${builder_evidence}/builder.cdx.json"
trivy_builder() {
  docker run --rm --platform linux/amd64 \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
    -v "${builder_evidence}:/evidence" \
    "${TRIVY_IMAGE}" "$@"
}
if trivy_builder image --download-db-only; then
  printf '0\n' > "${builder_evidence}/database-exit-status.txt"
else
  printf '%s\n' "$?" > "${builder_evidence}/database-exit-status.txt"
fi
if trivy_builder image --skip-db-update --scanners vuln --ignorefile /dev/null \
    --list-all-pkgs --exit-code 0 --format json --output /evidence/trivy-raw.json enterprise-shop/builder:ci; then
  printf '0\n' > "${builder_evidence}/scanner-exit-status.txt"
else
  printf '%s\n' "$?" > "${builder_evidence}/scanner-exit-status.txt"
fi
if trivy_builder image --skip-db-update --ignorefile /dev/null \
    --format cyclonedx --output /evidence/builder.cdx.json enterprise-shop/builder:ci; then
  printf '0\n' > "${builder_evidence}/sbom-exit-status.txt"
else
  printf '%s\n' "$?" > "${builder_evidence}/sbom-exit-status.txt"
fi
