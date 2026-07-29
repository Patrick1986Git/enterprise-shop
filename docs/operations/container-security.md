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

- Docker Hub, Hadolint `v2.14.0-alpine`: `docker.io/hadolint/hadolint:v2.14.0-alpine@sha256:7aba693c1442eb31c0b015c129697cb3b6cb7da589d85c7562f9deb435a6657c` (`linux/amd64` child manifest `sha256:be27962427a85de242820cb710a374478cce9bfb534a2c07e4fa54741d98908f`)
- GHCR, Trivy `0.72.0`: `ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f` (`linux/amd64`)
- Docker Hub, Go `1.25.7-bookworm`: `docker.io/library/golang:1.25.7-bookworm@sha256:564e366a28ad1d70f460a2b97d1d299a562f08707eb0ecb24b659e5bd6c108e1` (`linux/amd64` child manifest `sha256:58259daf0a27c150118663ef7452aa94d66a86d55e73b3443386146623f5364d`)
- Docker Hub, Alpine `3.20`: `docker.io/library/alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc` (`linux/amd64`)

The supplied Docker Hub references are multi-platform OCI index digests, and CI explicitly pulls and inspects their `linux/amd64` images. OCI digests are registry- and repository-scoped, so changing only the registry while reusing a digest does not produce a valid reference. Before linting or scanning, CI verifies manifest availability, the locally resolved platform, and each tool's version or release identity. For gosu source, tag `1.19` is the human-readable upstream release identity and full commit `6456aaa0f3c854d199d0f037f068eb97515b7513` (`Update to 1.19`) is the immutable security identity. CI shallow-clones the tag, verifies its peeled commit and `HEAD`, and fails before govulncheck and policy enforcement if either differs.

Dependabot checks Docker dependencies weekly in `/` and `/docker/postgres`. This covers both Eclipse Temurin stages in the application Dockerfile and the PostgreSQL 16 Alpine base in the PostgreSQL Dockerfile. Updates are proposed for review with the `build(deps)` prefix and are never merged automatically.

## Dockerfile linting policy

Hadolint enforces Dockerfile correctness and maintainability rules for both Dockerfiles. The CI command ignores only `DL3008` because the application runtime image intentionally receives security fixes from the maintained Ubuntu package repositories during image rebuilds instead of pinning a stale exact `apt` package version in source.

Local reproduction:

```bash
docker run --rm --platform linux/amd64 \
  -v "${PWD}:/workspace:ro" \
  -w /workspace \
  docker.io/hadolint/hadolint:v2.14.0-alpine@sha256:7aba693c1442eb31c0b015c129697cb3b6cb7da589d85c7562f9deb435a6657c \
  hadolint --ignore DL3008 Dockerfile docker/postgres/Dockerfile
```

Validate that Trivy itself can load the repository ignore policy before expensive image builds:

```bash
mkdir -p .tmp/container-security/trivy-cache

docker run --rm \
  -v "${PWD}/.trivyignore.yaml:/workspace/.trivyignore.yaml:ro" \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -w /workspace \
  ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f image --scanners vuln --severity CRITICAL --exit-code 0 --format table --ignorefile .trivyignore.yaml docker.io/library/alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
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

GOSU_SOURCE_TAG=1.19
GOSU_SOURCE_COMMIT=6456aaa0f3c854d199d0f037f068eb97515b7513

git clone \
  --depth 1 \
  --branch "${GOSU_SOURCE_TAG}" \
  --single-branch \
  https://github.com/tianon/gosu.git \
  .tmp/gosu-source
cd .tmp/gosu-source

resolved_tag_commit="$(git rev-parse "refs/tags/${GOSU_SOURCE_TAG}^{commit}")"
test "${resolved_tag_commit}" = "${GOSU_SOURCE_COMMIT}"
test "$(git rev-parse HEAD)" = "${GOSU_SOURCE_COMMIT}"
test -x govulncheck-with-excludes.sh
test -f version.go
grep -F 'const Version = "1.19"' version.go

GOLANG_IMAGE=docker.io/library/golang:1.25.7-bookworm@sha256:564e366a28ad1d70f460a2b97d1d299a562f08707eb0ecb24b659e5bd6c108e1 ./govulncheck-with-excludes.sh ./...
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
