# Container supply-chain security validation

CI validates the repository Dockerfiles and the final local images used for the Enterprise Shop application and custom PostgreSQL database. These checks add supply-chain visibility without publishing images or changing runtime application/database behavior.

## CI architecture

The `container-security` job is separate from Maven verification and functional Docker validation so failures are easy to classify:

- Hadolint checks the root `Dockerfile` and `docker/postgres/Dockerfile`.
- Docker builds CI-local images from fresh bases with `--pull`, tagged `enterprise-shop/app:ci` and `enterprise-shop/postgres:ci`.
- Trivy `0.72.0` scans each final image for operating-system and application/library vulnerabilities and reuses a GitHub Actions cache for the scanner database.
- Raw Trivy JSON reports are generated without policy filtering before any blocking vulnerability gate runs.
- Trivy generates CycloneDX JSON SBOMs for both images before policy enforcement.
- JSON vulnerability reports and SBOM files are uploaded as temporary GitHub Actions artifacts.
- Final CRITICAL policy scans run after evidence upload.

The existing `docker-validation` job remains responsible for Compose configuration checks, PostgreSQL/bootstrap behavior, the full Compose stack, and the health endpoint smoke check. A passing Compose healthcheck proves the services started successfully; it does not prove the images have no known vulnerabilities.

## Dockerfile linting policy

Hadolint enforces Dockerfile correctness and maintainability rules for both Dockerfiles. The CI command ignores only `DL3008` because the application runtime image intentionally receives security fixes from the maintained Ubuntu package repositories during image rebuilds instead of pinning a stale exact `apt` package version in source.

Local reproduction:

```bash
docker run --rm \
  -v "${PWD}:/workspace:ro" \
  -w /workspace \
  hadolint/hadolint:v2.12.0-debian \
  hadolint --ignore DL3008 Dockerfile docker/postgres/Dockerfile
```

## Vulnerability scanning policy

Trivy scans both final images with the `vuln` scanner. The policy is:

- `CRITICAL` vulnerabilities fail CI after raw reports and SBOMs are uploaded.
- `HIGH` vulnerabilities are reported in CI and JSON artifacts but do not fail CI initially, keeping remediation reviewable.
- Unfixed vulnerabilities are not ignored by default.
- Individual CVEs must not be silently suppressed.
- Raw scanner reports are evidence of everything Trivy detected; policy scans are the actionable gate after documented applicability analysis.
- Exceptions are not vulnerability fixes. An expired exception must be removed, renewed with fresh evidence, or replaced by a remediation before the expiry date.

Local reproduction:

```bash
mkdir -p .tmp/container-security/trivy-cache .tmp/container-security/reports .tmp/container-security/sbom

docker build --pull --tag enterprise-shop/app:ci .
docker build --pull --tag enterprise-shop/postgres:ci docker/postgres

docker image inspect enterprise-shop/postgres:ci --format '{{json .Id}} {{json .RepoTags}} {{json .RepoDigests}} {{json .Created}}'
docker run --rm --entrypoint gosu enterprise-shop/postgres:ci --version
```

Raw reports without policy filtering:

```bash
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -v "${PWD}/.tmp/container-security/reports:/reports" \
  ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f image --scanners vuln --severity HIGH,CRITICAL --exit-code 0 --format json --output /reports/enterprise-shop-app-trivy-raw.json enterprise-shop/app:ci

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -v "${PWD}/.tmp/container-security/reports:/reports" \
  ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f image --scanners vuln --severity HIGH,CRITICAL --exit-code 0 --format json --output /reports/enterprise-shop-postgres-trivy-raw.json enterprise-shop/postgres:ci
```

Final CRITICAL policy scans:

```bash
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f image --scanners vuln --severity CRITICAL --exit-code 1 --format table enterprise-shop/app:ci

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -v "${PWD}/.trivyignore.yaml:/workspace/.trivyignore.yaml:ro" \
  -w /workspace \
  ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f image --scanners vuln --severity CRITICAL --exit-code 1 --format table --ignorefile .trivyignore.yaml --show-suppressed enterprise-shop/postgres:ci
```

Use `--show-suppressed` on the PostgreSQL policy scan so reviewers can see when the scoped gosu exception was applied.

## gosu CVE-2025-68121 triage

CI run #481 failed only at PostgreSQL CRITICAL policy enforcement because Trivy detected `CVE-2025-68121` in the Go standard library metadata for `usr/local/bin/gosu`, inherited from the official `postgres:16-alpine` image. The Enterprise Shop PostgreSQL Dockerfile only copies Polish full-text-search dictionary files into that base image.

The official gosu security policy says generic binary scanners can report Go CVEs for packages that gosu never invokes and asks reporters to validate reachability with `govulncheck-with-excludes.sh`. gosu `1.19` source imports `os`, `os/exec`, `runtime`, `syscall`, `github.com/moby/sys/user`, and `golang.org/x/sys/unix`; it does not import or call `crypto/tls`. The CI job therefore runs the upstream gosu `1.19` govulncheck wrapper before applying the exception.

The repository-level `.trivyignore.yaml` contains one path-scoped exception:

- ID: `CVE-2025-68121`
- Path: `usr/local/bin/gosu`
- Expiry: `2026-10-31`
- Reason: upstream gosu govulncheck analysis classifies the affected `crypto/tls` TLS session-resumption certificate-validation path as unreachable from gosu `1.19`.

No package-wide, image-wide, wildcard, unfixed, or blanket Go standard-library suppression is configured. A different CRITICAL finding in gosu, PostgreSQL, Alpine, the Java runtime, or the application remains outside this exception and fails CI.

To reproduce the upstream gosu applicability check locally:

```bash
rm -rf .tmp/gosu-source
git clone --depth 1 --branch 1.19 https://github.com/tianon/gosu.git .tmp/gosu-source
cd .tmp/gosu-source
GOLANG_IMAGE=golang:1.25.7-bookworm ./govulncheck-with-excludes.sh ./...
```

## SBOM artifacts

An SBOM is a machine-readable inventory of image operating-system packages and application components. CI generates CycloneDX JSON SBOMs and uploads them as GitHub Actions artifacts:

| Image | SBOM artifact | File |
| --- | --- | --- |
| `enterprise-shop/app:ci` | `enterprise-shop-app-sbom` | `enterprise-shop-app.cdx.json` |
| `enterprise-shop/postgres:ci` | `enterprise-shop-postgres-sbom` | `enterprise-shop-postgres.cdx.json` |

Raw vulnerability scan JSON reports are uploaded as the `container-vulnerability-reports` artifact. Artifacts are retained for 14 days and can be downloaded from the completed workflow run page. Generated SBOMs and scan reports are temporary evidence and must not be committed.

Local SBOM generation and validation:

```bash
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -v "${PWD}/.tmp/container-security/sbom:/sbom" \
  ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f image --format cyclonedx --output /sbom/enterprise-shop-app.cdx.json enterprise-shop/app:ci

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -v "${PWD}/.tmp/container-security/sbom:/sbom" \
  ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f image --format cyclonedx --output /sbom/enterprise-shop-postgres.cdx.json enterprise-shop/postgres:ci

python -m json.tool .tmp/container-security/sbom/enterprise-shop-app.cdx.json >/dev/null
python -m json.tool .tmp/container-security/sbom/enterprise-shop-postgres.cdx.json >/dev/null
```
