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


The workflow event matrix is intentionally narrow:

| Event | `build` | `docker-validation` | `container-security` | `deploy-pages` |
| --- | --- | --- | --- | --- |
| Pull request | Yes | Yes | Yes | No |
| Push to `master` | Yes | Yes | Yes | Yes, after `build` |
| Weekly schedule | No | No | Yes | No |
| Manual `workflow_dispatch` | No | No | Yes | No |

The scheduled run starts every Monday at `04:23 UTC` (`23 4 * * 1`). Maintainers can also select **CI** under the repository's **Actions** tab and use **Run workflow**; scheduled and manual runs explicitly check out `master`. These runs rebuild both CI-local images with `--pull` and never publish them. Recurring scans matter because vulnerability intelligence and upstream base images change without a repository commit: a new non-excepted CRITICAL finding is therefore detected by the next run.

The external container tools and scan input are immutable while retaining readable source versions:

- GHCR, Hadolint `v2.12.0-debian`: `ghcr.io/hadolint/hadolint:v2.12.0-debian@sha256:6c4b7c23b39e25e4738b7cb37ed0b89f421830a3c7be5d79d0ec4a27f0fefee0` (`linux/amd64`)
- GHCR, Trivy `0.72.0`: `ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f` (`linux/amd64`)
- Docker Hub, Go `1.25.7-bookworm`: `golang:1.25.7-bookworm@sha256:903a5c4789afee266d1cb616e98b214a8ad7a1b5eece8d422f5c6207d1d8e63f` (`linux/amd64`)
- Docker Hub, Alpine `3.20`: `alpine:3.20@sha256:1e42bbe2508154c9126cf75e4a6ddc0189516c9f452523a3c721f91954a8d017` (`linux/amd64`)

Before linting or scanning, CI pulls every exact reference for `linux/amd64` and runs a version or release identity check. This separates a missing or wrong-platform OCI reference from a tool finding in this repository. The gosu source is separately pinned to commit `9f7cd138a1edb3be0f95f6a8f0a3cf865e1f3172`, the commit referenced by the official `1.19` tag; CI fetches that commit directly and verifies `HEAD` before invoking the upstream wrapper.

Dependabot checks Docker dependencies weekly in `/` and `/docker/postgres`. This covers both Eclipse Temurin stages in the application Dockerfile and the PostgreSQL 16 Alpine base in the PostgreSQL Dockerfile. Updates are proposed for review with the `build(deps)` prefix and are never merged automatically.

## Dockerfile linting policy

Hadolint enforces Dockerfile correctness and maintainability rules for both Dockerfiles. The CI command ignores only `DL3008` because the application runtime image intentionally receives security fixes from the maintained Ubuntu package repositories during image rebuilds instead of pinning a stale exact `apt` package version in source.

Local reproduction:

```bash
docker run --rm --platform linux/amd64 \
  -v "${PWD}:/workspace:ro" \
  -w /workspace \
  ghcr.io/hadolint/hadolint:v2.12.0-debian@sha256:6c4b7c23b39e25e4738b7cb37ed0b89f421830a3c7be5d79d0ec4a27f0fefee0 \
  hadolint --ignore DL3008 Dockerfile docker/postgres/Dockerfile
```

Validate that Trivy itself can load the repository ignore policy before expensive image builds:

```bash
mkdir -p .tmp/container-security/trivy-cache

docker run --rm \
  -v "${PWD}/.trivyignore.yaml:/workspace/.trivyignore.yaml:ro" \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -w /workspace \
  ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f image --scanners vuln --severity CRITICAL --exit-code 0 --format table --ignorefile .trivyignore.yaml alpine:3.20@sha256:1e42bbe2508154c9126cf75e4a6ddc0189516c9f452523a3c721f91954a8d017
```

## Vulnerability scanning policy

Trivy scans both final images with the `vuln` scanner. The policy is:

- `CRITICAL` vulnerabilities fail CI after raw reports and SBOMs are uploaded.
- `HIGH` vulnerabilities are reported in CI and JSON artifacts but do not fail CI initially, keeping remediation reviewable.
- Unfixed vulnerabilities are not ignored by default.
- Individual CVEs must not be silently suppressed.
- Raw scanner reports are evidence of everything Trivy detected; policy scans are the actionable gate after documented applicability analysis.
- Exceptions are not vulnerability fixes. An expired exception must be removed, renewed with fresh evidence, or replaced by a remediation before the expiry date. Trivy `0.72.0` requires `expired_at` in `.trivyignore.yaml` to be an RFC 3339 timestamp, so the configuration uses the end of the UTC calendar day.

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
- Expiry: `2026-10-31` (`expired_at: "2026-10-31T23:59:59Z"`)
- Reason: upstream gosu govulncheck analysis classifies the affected `crypto/tls` TLS session-resumption certificate-validation path as unreachable from gosu `1.19`.

No package-wide, image-wide, wildcard, unfixed, or blanket Go standard-library suppression is configured. A different CRITICAL finding in gosu, PostgreSQL, Alpine, the Java runtime, or the application remains outside this exception and fails CI. Scheduled scans keep the CVE visible in the unfiltered raw report, and the policy gate fails once the exception expires. The `2026-10-31` deadline is not automatically extended; changing it requires a reviewed source change supported by fresh evidence.

To reproduce the upstream gosu applicability check locally:

```bash
rm -rf .tmp/gosu-source
git init .tmp/gosu-source
cd .tmp/gosu-source
git remote add origin https://github.com/tianon/gosu.git
git fetch --depth 1 origin 9f7cd138a1edb3be0f95f6a8f0a3cf865e1f3172
git checkout --detach FETCH_HEAD
test "$(git rev-parse HEAD)" = "9f7cd138a1edb3be0f95f6a8f0a3cf865e1f3172"
GOLANG_IMAGE=golang:1.25.7-bookworm@sha256:903a5c4789afee266d1cb616e98b214a8ad7a1b5eece8d422f5c6207d1d8e63f ./govulncheck-with-excludes.sh ./...
```

## SBOM artifacts

An SBOM is a machine-readable inventory of image operating-system packages and application components. CI generates CycloneDX JSON SBOMs and uploads them as GitHub Actions artifacts:

| Image | SBOM artifact | File |
| --- | --- | --- |
| `enterprise-shop/app:ci` | `enterprise-shop-app-sbom` | `enterprise-shop-app.cdx.json` |
| `enterprise-shop/postgres:ci` | `enterprise-shop-postgres-sbom` | `enterprise-shop-postgres.cdx.json` |

Raw vulnerability scan JSON reports are uploaded as the `container-vulnerability-reports` artifact. Artifacts are retained for 14 days and can be downloaded from the **Artifacts** section of the completed pull request, push, scheduled, or manual workflow run page. Generated SBOMs and scan reports are temporary evidence and must not be committed.

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
