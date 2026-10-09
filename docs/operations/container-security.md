# Container supply-chain security validation

## Repository-owned CodeQL source analysis

[CodeQL](../../.github/workflows/codeql.yml) owns two independent jobs with the same
reviewed SHA-pinned action and `security-extended` query policy. `Analyze Java/Kotlin`
retains Java 21, manual database population through the reviewed Ryuk preparation
and full `./mvnw -B clean verify`, and its existing Code Scanning category. `Analyze
Python` uses `build-mode: none` on a separate hosted runner. It extracts the clean
checkout without executing candidate scripts, installing dependencies, building
Java, or using Docker. Its explicit `/language:python` category keeps its processed
SARIF upload separate from the existing Java analysis.

Both jobs run for pull requests targeting master, protected-master pushes, the
existing weekly schedule, and explicit dispatch. Checkout disables persisted
credentials and selects protected master for the schedule, the immutable PR HEAD
for pull requests, and the selected ref for push/dispatch. The workflow defaults to
`contents: read`; each analysis job alone adds `security-events: write`. There is
no `pull_request_target`, additional secret, job dependency, container, or service.

The Python source boundary includes every Git-tracked `*.py` file, including all
policy tests. New repository directories are covered without an allowlist update.
The security-sensitive tooling currently has these responsibilities:

| Sources under `scripts/` | Security responsibility |
| --- | --- |
| `validate-auxiliary-containers.py`, `validate-build-tool-vulnerabilities.py`, `validate-container-vulnerability-policy.py`, `scan-build-tools.py` | Fail-closed advisory policy, Trivy/CycloneDX identity and execution ownership; subprocess and environment inputs. |
| `build-ryuk-candidate.py`, `ryuk_candidate_evidence.py`, `ryuk_client_tests.py`, `check-ryuk-cleanup.py` | Verified source/archive inputs, deterministic builds, source/binary govulncheck, reviewed executable and test identities, real cleanup evidence. |
| `auxiliary_binary_evidence.py`, `scan-auxiliary-containers.py`, `verify-trivy-provenance.py` | External artifact downloads, tar-member readback, OCI/executable/SBOM identities, scanner provenance and Docker subprocess boundaries. |
| `build-tool-inventory.py`, `validate-builder-security.py` | Maven collector/classpath/execution evidence, ZIP readback, external JDK advisory downloads and checksum verification. |
| `validate-github-actions-policy.py`, `validate-master-protection.py`, `validate-maven-wrapper.py`, `restore_pr_scope.py` | Pinned Actions and permissions, live protected-master policy, distribution integrity, immutable changed-file detection and restore decisions. |
| `prepare-jacoco-ratchet.py`, `validate-jacoco-report.py` | Downloaded artifact integrity and provenance, untrusted coverage XML/JSON, exact non-regression and ratchet decisions. |
| `openapi_compatibility.py`, `validate_migration_compatibility.py`, `validate-production-configuration.py` | Untrusted contract/schema/configuration inputs, compatibility decisions and operational safety. |
| `validate-codeql-python-coverage.py` and `tests/*.py` | Actual extracted-source/query/SARIF evidence, archive readback and deterministic offline regressions for these boundaries. |

The job places CodeQL databases and SARIF under `runner.temp`, outside the source
checkout. It creates no Maven output, downloaded third-party source, dependency
cache or generated Python inputs there. It deliberately uses no source filters.
Committed Python files are repository-owned inputs; adding vendored/generated
sources requires review rather than silently excluding a tracked file.

After successful analysis and processed SARIF upload, the job decodes CodeQL's
built-in `Diagnostics/ExtractedFiles` query. The offline coverage verifier requires
every tracked Python path to appear in the actual database query and its archived
source bytes to match the checkout. Missing/empty/partial extraction, changed
bytes, linked sources or unsuccessful query execution fail the job. The
`python-codeql-evidence` artifact retains the exact HEAD, per-file SHA-256 inventory,
decoded query results and Python SARIF for 14 days. A green analysis or a nonempty
archive alone is insufficient. The verifier runs after extraction; it does not
execute scripts to populate the database. Findings remain visible in SARIF and
Code Scanning for investigation, with no suppression or severity downgrade.

Expect one additional short Python runner job per CodeQL event, generally about
one minute for this repository, subject to runner startup, bundle/cache and query
cost. No additional complete Maven build is added. Offline policy regressions
complement this source analysis; advisory scanning, provenance and actual runtime
tests retain their separate ownership. Static extraction without installing
dependencies has limited visibility into dynamically imported/generated code and
external modules. CodeQL does not establish absence of vulnerabilities or complete
coverage for shell/Windows Wrapper, workflow expression semantics, downloaded Go
tools, native Haskell/C++ binaries, registries, compilers or hosted runner/daemon
infrastructure. Maven, Trivy, govulncheck and existing integrity policies continue
to cover their reviewed supply-chain boundaries.

### Required Python status migration: temporary preparation

Ruleset `Protect master` (`20755388`) currently requires the exact seven contexts
`build`, `docker-validation`, `container-security`, `Analyze Java/Kotlin`,
`dependency-review`, `restore-pr-scope` and `restore-rehearsal`, all from GitHub
Actions integration `15368`. `Analyze Python` is independently successful but is
not currently required. The intended final contract adds that exact context and
retains all seven existing checks, strict up-to-date status validation, review
thread resolution and the current bypass policy.

This draft contains a temporary validator accepting only the exact old seven or
the exact intended eight, with identical trusted integration IDs in both states.
Every other subset, added/renamed/duplicated context or untrusted integration fails.
This is preparation, not the permanent policy or a merge-ready migration. The
seven-check alternative must be removed before this draft is accepted as final.

The current protected-master source still requires exactly seven. Installing the
ruleset's eighth check now would cause a master CI rerun to fail its existing
validator. Conversely, merging a strict-eight validator before the live change
would fail the new master's live check. A transition only on the PR branch does
not resolve that protected-master sequencing constraint.

For a migration that preserves successful master validation throughout:

1. A maintainer must first arrange a separately reviewed minimal bootstrap of the
   exact seven/eight transition validator and its tests on protected master, and
   verify that master's CI accepts the unchanged seven-check live ruleset. This
   requires an explicit exception to keeping every change solely in draft PR
   #467; this task does not create another branch/PR, update master or merge.
2. Only after that compatible master revision is verified may the maintainer add
   exactly `Analyze Python`, GitHub Actions integration ID `15368`, to ruleset
   `20755388`. Preserve all seven existing contexts and every other rule. Confirm
   the live eight-check representation and the bootstrap master's policy check.
3. Continue in PR #467: incorporate that exact protected base, remove the temporary
   seven-check acceptance, make fixtures/regressions/documentation enforce exactly
   eight, and rerun all final-head CI and both CodeQL jobs before maintainer review.

Under the original single-PR/no-merge constraints alone, there is no atomic change
covering GitHub administration and the validator on two Git revisions. A brief
master-validator incompatibility window would require a separate explicit
maintainer decision; it is not silently accepted here. Do not add the eighth
status merely because the draft's temporary validator is green. No administrative
settings are changed by this draft.

### OCI provenance finding and tested trust boundary

`py/incomplete-url-substring-sanitization` reports the registry prefix check in
`auxiliary_binary_evidence.provenance`. This helper is not a parser for arbitrary
untrusted references: an isolated caller can inject token query parameters using
an otherwise Docker-prefixed repository string. The actual collector calls
`validate-auxiliary-containers.inventory` first. Its official comparison check
requires `docker.io/testcontainers/ryuk:0.14.0@sha256:`; unlike ordinary inventory
images, this comparison uses that fixed prefix rather than the generic `IMAGE`
regex. Thus `removeprefix('docker.io/').split(':', 1)[0]` is necessarily the exact
`testcontainers/ryuk` repository. Registry, namespace and tag changes or delimiters
before the tag fail that real caller boundary before any provenance request.

Malformed tails after the fixed tag cannot change the repository/authentication
scope or fixed HTTPS authentication and registry hosts. This is not a claim that
the prefix validates all OCI syntax. Collection verifies pulled/platform image
identity and actual index/manifest/configuration bytes; retained evidence binds
the measured index digest to the reference. Provenance manifest/blob responses
must match their reviewed SHA-256 values and source/subject relationships.

Seven deterministic mocked-network regressions exercise actual census rejection,
misleading registries, credentials, query/fragment/encoded delimiters, path
components, malformed digest tails, all three request URLs and exact read-only
token scope, response integrity and the isolated-helper counterexample. Within
the reviewed caller/data flow the CodeQL finding is not exploitable by these
inputs, so no speculative production correction is made. The finding remains
visible for explicit maintainer triage, with no suppression, downgrade or automatic
dismissal. Altering reviewed repository code/inventory, upstream HTTPS redirects,
or the external registry itself remains a separate trust boundary.

## Auxiliary execution images

The authoritative [auxiliary inventory](../../.github/security/auxiliary-container-scope.json) records exact tags/digests, registries, linux/amd64, executable byte/version identities, commands, mounts, Docker-socket/network capabilities, inputs, code execution, update/advisory owners, SBOM coverage and classifications. Whole-source SHA-256 receipts cover workflow, Docker/Compose, shell/Python Docker orchestration and Java Testcontainers image surfaces. New surfaces and changes to existing ones fail inventory validation until the image census/classification and source receipts are deliberately reviewed. This is a review boundary for those sources, not a complete interpreter of arbitrary downloaded or dynamically generated code.

| Image | Capability and owned advisory boundary |
| --- | --- |
| Hadolint v2.14.0 scratch | Parses candidate Dockerfiles with repository read-only access and networking disabled. No OS packages. Its static Haskell executable/dependency graph is not represented by Trivy; upstream Hadolint/Haskell advisory review remains necessary. |
| Trivy 0.75.0 | Security authority with Docker-socket access in existing image scans and writable evidence/cache. Auxiliary scans use read-only exported archives without a socket. OS/Go-package self-scan is advisory visibility, not independent scanner integrity proof. |
| Go 1.26.9 Alpine 3.24 | Installs and executes govulncheck v1.1.4 against pinned gosu source; read-only source, writable named cache, Go-service networking, no socket. Results control PostgreSQL exception applicability. OS/Go-toolchain package findings block at HIGH/CRITICAL. |
| Alpine 3.20 fixture | Immutable target for Trivy ignorefile/configuration regression plus release-identity probe; no analysis authority or candidate-code execution. End-of-life, with no current audit findings. It is mechanically excluded from trusted execution evidence and supplies no exception authority. Its role does not require deliberate vulnerabilities. |
| Rebuilt Ryuk 0.14.0 candidate | Runner-local scratch reaper, exact release source plus reviewed Moby client/API migration, Go 1.26.9 and unchanged cleanup algorithm. Content-addressed configuration, repeat executable builds, direct raw/SBOM, source/binary govulncheck, socket/execution/cleanup assertions. No CVE exceptions. |

Ryuk's [0.14.0 release](https://github.com/testcontainers/moby-ryuk/releases/tag/0.14.0), source commit `b3726afd6cc2c36628abcc08e9cabac43f587384`, introduced UPX compression. Its exact Linux Dockerfile uses Go 1.23/Alpine 3.22, `CGO_ENABLED=0`, `go build -a -installsuffix cgo -ldflags="-w -s" -trimpath`, then `upx --best --lzma`; scratch contains only the executable and CA certificates. The 0.13.0 comparison changes Dockerfiles only and does not justify a downgrade. Recovery measures Go **1.23.12**, main module `github.com/testcontainers/moby-ryuk` at `(devel)` with no embedded VCS revision, and **19 embedded dependencies**. Their versions and h1 sums must match exact upstream go.mod/go.sum; source files corroborate binary metadata rather than replacing it.

The inventory's schema 3 `evidence_transform` expresses a measured compressed Go path. The official comparison image remains `docker.io/testcontainers/ryuk:0.14.0@sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0`, amd64 manifest `sha256:f0456560ea5b4acdbed0da0efc33b5f9dd6bc1e59f2337106826dcb5b0b0e981`, config `sha256:9a5f93e8f9300530b91866bcf41c0b8a5d72f0e5a7b4365167a48df9816cd36e`. Registry index/manifest bytes, saved configuration and every uncompressed scratch layer are hashed before reading the filesystem. Compressed `/bin/ryuk` SHA-256 is `5aa022d7dcdc90eb4072a44b6f54b9b62d5fbd126de320cd56e04db4938e4e38`; its marker identifies UPX **5.02**. Mandatory UPX `-t`, two independent `-d -o` outputs and Linux/amd64 ELF checks establish deterministic decompressed SHA-256 `54b194bbf7e39ffc7faf132ccf7dc694a409a4db3c1cfb9a889299827078fae8`. Original archived bytes remain available for comparison. Evidence collection never executes the recovered payload.

Official UPX **5.2.1** linux/amd64 release asset is pinned to archive SHA-256 `402162aad30af47e60dbd767fb2e64ca394ace9727ba1f40283641f1d1b91657` and executable SHA-256 `287b3dffe9dcafd8e366e162ac4ab41e5cf45a3c6768970256af0869288d84a1`. Published release checksum and reviewed bytes establish integrity; no signed UPX build provenance was established. This new tool has an explicit `isolated-evidence-transformer` census entry and manual maintainer ownership of upstream UPX NEWS/security issues. Trivy does not inventory its static C++ libraries, so complete native-library advisory coverage is not claimed. It runs in the existing governed Go image with no network/socket/secrets/repository mount, nonroot user, read-only rootfs/input/tool, only temporary payload output writable, dropped capabilities, no-new-privileges and resource limits. It does not become another vulnerability authority.

The decompressed file is mounted read-only as `/analysis/bin/ryuk` for the same pinned Trivy 0.75.0 `rootfs` authority. Raw `ArtifactType=filesystem` and SBOM `/analysis` identity distinguish it from a production container. Both executable identities, the reviewed transformation/tool/source receipts, raw/SBOM hashes and exact compiler/module readback are required at enforcement. The 21 recovered PURLs comprise 19 dependencies, stdlib and the unversioned `(devel)` root module. That root's absence of a release version is explicit binary evidence, not a package exception. The original compressed image's zero-package report is retained separately and cannot pass as clean. Initial measured analysis on 2026-10-08 finds **23 HIGH and 1 CRITICAL**; authoritative current hosted evidence decides the gate. There are zero exceptions. Comparison readback does not accept those advisories or become the rebuilt candidate’s security result.

Pinned govulncheck v1.1.4 also analyzes the exact recovered binary in binary mode with `GOVERSION=go1.23.12`. Its JSON, stderr and status are retained; analyzer/database errors block. Findings exit status 3 remains evidence, not an exemption. Binary analysis of a stripped executable cannot prove source-level call-path reachability, configuration, or applicability. Package/version HIGH/CRITICAL findings remain authoritative even if govulncheck reports fewer reachable symbols.

The pinned OCI index includes an unsigned SLSA v0.2 BuildKit statement, layer `sha256:e101a7504012f07ce2375a5d18ebc748525019bd013d42830db9c62dbb2bb4e5`, under attestation manifest `sha256:df604959c6dcf62c3e5d87ed85ec9cfae1ca0c3a846c143f5919ad564ae14c73`. Its subject is the exact amd64 digest, VCS metadata names the reviewed source commit/repository, and builder claims [official release run 18032732627](https://github.com/testcontainers/moby-ryuk/actions/runs/18032732627), independently observed successful at that commit. Collector retains and verifies digest/subject/source relationships. This strengthens source corroboration but does not verify a signed workflow/builder identity; the statement declares incomplete materials and `reproducible=false`. No controlled rebuild is needed to recover package identities, and byte-identical source-to-binary reproduction is not claimed.

### Reviewed unpublished Ryuk rebuild

The [candidate receipt](../../.github/security/ryuk/candidate.json) and [production patch](../../.github/security/ryuk/moby-client.patch) select the exact official 0.14.0 commit and SHA-256-verified codeload archive. No post-release upstream commits are included. Current main at `84f4ff002cee21da147748b986d393f2e5d79345` still uses legacy Docker `v28.5.2+incompatible`; its merged Go change `cefd12788475f37b4dcfc0866708a224a29fabe5` independently supports the maintained Go 1.26 build line. Taking main wholesale would also include unrelated workflow, test-dependency and lint changes. The rebuild keeps release application logic and uses a focused dependency/API migration instead. The production patch changes only interfaces, client calls and dependency files. Repository PostgreSQL/Testcontainers integration tests validate candidate execution and cleanup.

Candidate validation selects upstream `Test_loadConfig` and all fourteen mocked `Test_newReaper_Run` scenarios into an isolated test overlay, adapting only Moby API types, result wrappers and negotiated Ping expectations. This covers session-label filters, all four resource list/remove paths, changed-resource waiting, not-found handling and list/remove failures. Four focused contract cases additionally check negotiated Ping failure, filter order/deduplication, cleanup sequencing/options and transient removal retry. Tests use the same immutable Go builder, reviewed release go.sum checksums and readonly module resolution, with no external network or Docker socket during execution. Test source hashes, exact passed test names and JSON output/status are required evidence. Test-only module additions do not change production source, embedded dependencies or image bytes.

Upstream daemon-backed `Test_newReaper`, `TestReapContainer`, `TestReapNetwork`, `TestReapVolume` and `TestReapImage` are not ported: they require the removed legacy API, arbitrary Alpine pulls and Docker image-build/archive APIs outside Ryuk's execution contract. Abort/shutdown timing tests are outside this focused migration subset. The mocked scenarios and repository's actual PostgreSQL/Testcontainers/post-JVM cleanup checks remain complementary; complete upstream Docker-resource integration coverage is not claimed. Local preparation and validated reuse are documented in [local development](local-development.md#maven-and-testcontainers-verification).

All starting Trivy advisory IDs, severities and published fixed versions are recorded in [comparison advisories](../../.github/security/ryuk/official-0.14.0-advisories.json); the separate binary govulncheck advisory inventory records module ranges. The 21 HIGH stdlib findings require at least Go 1.26.6; CRITICAL CVE-2025-68121 was already fixed on the 1.26 release line. The selected immutable `golang:1.26.9-alpine3.24` builder is governed for OS/compiler advisories. `CGO_ENABLED=0`, linux/amd64/v1, `-a -installsuffix cgo -ldflags="-w -s" -trimpath` remain; `-mod=readonly -buildvcs=false` prevents graph changes and location-dependent VCS metadata. Two builds must produce identical bytes.

Protected-master verification after PR #460 encountered standard-library advisories from the Go database dated `2026-10-08T22:31:09Z`. The retained [Go 1.26.8 advisory evidence](../../.github/security/ryuk/go-1.26.8-advisories.json) records CI #1158's authenticated artifact, 13 distinct source IDs across 410 findings, and 12 binary IDs across 17,860 findings. Each affected range and CVE alias was independently checked against the official Go vulnerability records. [Go 1.26.9, released October 8, 2026](https://go.dev/doc/devel/release#go1.26.9), fixes this set. The upgrade changes the compiler and executable/image identities while retaining the exact reviewed Ryuk production source, Moby migration patch and module checksums. The patch's `go 1.26.8` directive remains the minimum source language/toolchain requirement; actual compiler, analyzer and executable readback must be Go 1.26.9. Package-level findings still block without a reachable-symbol exception.

Current public legacy versions end at Docker 28.5.2. Reviewed Go advisories [GO-2026-5746](https://pkg.go.dev/vuln/GO-2026-5746) / CVE-2026-41567 and [GO-2026-5617](https://pkg.go.dev/vuln/GO-2026-5617) / CVE-2026-42306 have no fixed legacy module version. Their daemon functions are `Daemon.containerExtractToDir` (archive upload/host execution) and `Daemon.openContainerFS` (docker-cp bind-mount redirection). The engine module `github.com/moby/moby/v2` is fixed from `v2.0.0-beta.14`, but Ryuk requires neither that engine nor its daemon. The supported split modules `github.com/moby/moby/client v0.6.1` and `github.com/moby/moby/api v1.56.1` replace the monolithic dependency; both require Go 1.24 or newer. These are current public module releases, not a private replacement of Docker.

The migration preserves session-label filter JSON, list/remove routes, container force/volume removal, image prune-child options and error handling. List response wrappers expose Items; client option types replace API option types; negotiated Ping handles API negotiation. The supported client’s minimum API is 1.40, compatible with the repository’s maintained Docker runners. The exact 19 embedded module versions/h1 checksums are inventoried, including updated docker/go-connections, image-spec and OpenTelemetry dependencies. The source dependency graph must match every embedded dependency and must contain no legacy Docker or Moby daemon module/package. Current govulncheck v1.8.0 analyzes the exact patched production main in source mode and the resulting binary; both must have zero findings, correct Go/module identity, tool version/checksum and retained statuses. No non-applicability mechanism is installed: every HIGH/CRITICAL still blocks, and any attempt to add applicability decisions fails closed.

The candidate omits UPX to reduce parser and evidence complexity. Its scratch layer contains only the executable and CA material copied from the immutable builder. No shell, package manager or runner proxy CA is introduced. Canonical configuration and tar headers bind all contents to a reproducible local reference: `local/enterprise-shop-ryuk:sha256-0c88a43b0dbf5f47247aae36c687e69bda0ec11d49d198f3e2f7a77932a44e2a`. This suffix is the **image configuration** digest, not a registry manifest digest. Binary SHA-256 is `6d1c41cdd3909665d43de14dbfc344e0a5e3486efe399bbc0aa286ea98498fce`. No private image is published; each CI/CodeQL checkout builds and loads its own exact candidate. Missing local build or any source, dependency, executable, CA, archive/configuration drift fails. The official compressed image’s entire OCI/UPX/decompression/source/binary chain remains collected and validated under `official-ryuk-comparison`, with no execution authority.

Go 1.26.9 [hosted proof at 8176b025](https://github.com/Patrick1986Git/enterprise-shop/actions/runs/37881363846/artifacts/11594881209) independently matches both local executable/packaging builds and returns zero source/binary findings against the authoritative Go service. Compressed offline fixtures retain the complete authentic reports and their artifact/hash provenance; tests evaluate their historical observation time without changing runtime freshness. Every final-head hosted workflow repeats the full build and advisory validation. Previous Go 1.26.8 [build proof at 48e91592](https://github.com/Patrick1986Git/enterprise-shop/actions/runs/37772549705/artifacts/11548487975) matched local repeat executable/configuration/archive identities and returned zero source/binary govulncheck findings. The final patch retains upstream’s original test-only testify version; every final-head build rechecks the resulting exact source receipt. Testcontainers properties select only the fully identified candidate. RyukExecutionIT proves the running local reference, expected configuration ID and writable `/var/run/docker.sock` mount; after the Maven JVM exits, cleanup verification requires removal of the observed reaper and PostgreSQL resources. The socket remains a privileged execution boundary: this remediation does not patch or attest the external Docker daemon, and arbitrary socket access would still permit host-level Docker operations.

Strict Trivy schema/UpdatedAt/NextUpdate freshness is unchanged. Source/binary repair never rewrites DB timestamps, ignores unfixed findings, suppresses scanner errors or converts the upstream publication outage into an exception. If final-head builds/tests pass but authoritative DB NextUpdate is expired, the draft remains `UPSTREAM_DB_BLOCKED` and is not merge-ready.

The advisory-driven changes preserve other boundaries. Hadolint changes to upstream scratch with the **identical** v2.14.0 executable SHA-256, removing 19 HIGH and 2 CRITICAL inherited OS-package findings without upgrading the linter. Trivy 0.72.0 had 41 HIGH findings; 0.75.0 has no current HIGH/CRITICAL package findings. Go 1.25.7 Bookworm had affected Go binaries and 790 blocking OS findings. Same-line 1.25.14 Bookworm repairs Go but retains 419 OS findings, including 301 without a published fix; its Alpine variant still has two OpenSSL HIGH findings. The reviewed 1.26.9 Alpine image has no HIGH/CRITICAL OS/toolchain findings; its MEDIUM zlib finding remains visible. Counts are time/database-specific observations, not security guarantees.

`run-gosu-govulncheck.sh` measures the installed PostgreSQL gosu compiler version using Go binary metadata and forwards that version as GOVERSION to the unchanged upstream reachability/filtering wrapper. Upgrading the analysis compiler therefore does not erase installed-binary advisories by analyzing only a newer standard-library version. The downloaded analyzer/modules and named cache remain separate trust boundaries; the Go image SBOM does not cover every subsequent download.

`scan-auxiliary-containers.py` records the source/index/amd64 manifest and matching image configuration, reads installed apk packages and executable hashes from exported archives, and requires executable version identity. It reuses the builder's successful database refresh/cache. One unfiltered `--list-all-pkgs --ignorefile /dev/null` image report supplies both policy and converted CycloneDX 1.7 evidence. It does not rescan to generate the SBOM. Raw package PURLs must match the SBOM, installed OS packages must read back, and required Go binaries/compiler versions must appear. Scanner/database/conversion failure, stale database provenance, missing evidence, or wrong source/platform/version/image identity fails closed. Explicitly empty static Hadolint package coverage, bound to its reviewed binary hash, cannot authorize Ryuk's missing Go readback.

The existing container-security job uploads `auxiliary-container-evidence` with always() before the auxiliary gate, then `auxiliary-container-policy-result`, both for fourteen days. Evidence retains source SHA, exact reference/amd64 resolution, registry/Docker metadata, raw reports, SBOMs, statuses, timestamp/database provenance, executable identity and decision. Transient external-image exports are deleted; the deterministic candidate archive and layer are retained for verification. Every recognized HIGH/CRITICAL finding blocks, including unfixed findings. There are no inherited exceptions, wildcard packages, severity reductions, image exclusions or CI-only exemptions. Fixture evidence cannot enter this gate. Monday 04:23 UTC scans use protected master; PR execution uses exact head with contents: read and disabled persisted checkout credentials. No repository secrets or privileged PR event are introduced.

### Scanner provenance and bootstrap limits

Aqua's official release workflow publishes Cosign image-manifest signatures, Sigstore asset bundles and GitHub SLSA provenance for checksum-listed archives. The design inspected the pinned upstream workflow, GoReleaser configuration and signature documentation before selecting verification. Existing hosted-runner gh verifies the official Linux/amd64 archive attestation with repository, source-ref/tag and signer-workflow constraints. `verify-trivy-provenance.py` downloads the archive **as data**, verifies its published asset digest and attestation, then requires its scanner executable bytes to match the pinned image and reviewed hash. It installs or executes no downloaded verifier/scanner archive and adds no vulnerability database. The ephemeral standard GitHub token is available only to host gh verification, never to a tool image.

This supplies independent scanner **executable provenance** through Aqua/GitHub/Sigstore and existing runner/gh trust roots. It does not verify the image's Cosign signature, attest every filesystem file, independently audit scanner source, or make self-scan output independent. Immutable digest/amd64/configuration checks establish image identity; self-scan supplies package advisory visibility. Upstream release/signing compromise, runner/gh compromise, scanner defects and advisory publication omissions remain residual boundaries. Every self-scan policy record explicitly denies independent integrity assurance.

### Update authority and infrastructure boundaries

Dependabot Docker directories are root and docker/postgres. The inspected [GitHub Actions parser](https://github.com/dependabot/dependabot-core/blob/3b68008e805baffb205e066abc61522083cb3f8b/github_actions/lib/dependabot/github_actions/file_parser.rb) reads uses declarations and excludes Docker references. Workflow env image strings and Ryuk's properties entry therefore lack automated update coverage. No repository workflow submits these image packages to GitHub's dependency graph; scanning does not imply Dependency Review coverage.

Maintainers own manual upstream release/advisory review; existing weekly rescans refresh recognized-package advisories without rewriting digests. No silent updater exists. Version/digest changes require normal PR review, amd64 resolution, executable/version checks, raw advisory/SBOM evidence and hosted regression compatibility. Receipt updates must review any new image's classification and ownership. Successful package scans do not automate advisory review for static/opaque binaries.

Application/JRE, PostgreSQL/base/bootstrap/test images, builder OS/JDK and Maven executable tooling keep separate ownership policies. Production PostgreSQL scans do not attest every test-base byte. GitHub runner provisioning, runner Docker daemon/BuildKit/default Dockerfile frontend/docker-init, downloaded analyzer modules, vendor/database completeness and sources outside the census remain distinct trust limits. Ryuk remediation requires fresh authoritative final-head package evidence; UPX comparison-tool native-library coverage, authenticated upstream image/source provenance and downloaded analyzer provenance remain separate limits. This policy does not claim all CI tools or the entire supply chain are covered.

CI validates the repository Dockerfiles, the application Docker builder stage, and the final local images used for the Enterprise Shop application and custom PostgreSQL database. These checks add supply-chain visibility without publishing images or changing runtime application/database behavior.

## CI architecture

The `container-security` job is separate from Maven verification and functional Docker validation so failures are easy to classify:

- Hadolint checks the root `Dockerfile` and `docker/postgres/Dockerfile`.
- Docker builds CI-local images from fresh bases with `--pull`, tagged `enterprise-shop/builder:ci`, `enterprise-shop/app:ci` and `enterprise-shop/postgres:ci`. Builder/runtime exports preserve BuildKit base and image provenance.
- Trivy `0.75.0` scans each final image for operating-system and application/library vulnerabilities and reuses a GitHub Actions cache for the scanner database.
- Raw Trivy JSON reports are generated without policy filtering before any blocking vulnerability gate runs.
- Trivy generates separate CycloneDX JSON SBOMs for the builder, application and PostgreSQL images before policy enforcement. The tar-installed Temurin JDK has a separate vendor advisory gate because Trivy does not recognize that distribution.
- JSON vulnerability reports and SBOM files are uploaded as temporary GitHub Actions artifacts.
- Component-aware HIGH validation and final blocking policy scans run after evidence upload.

The existing `docker-validation` job remains responsible for Compose configuration checks, PostgreSQL/bootstrap behavior, the full Compose stack, and the health endpoint smoke check. A passing Compose healthcheck proves the services started successfully; it does not prove the images have no known vulnerabilities.


The workflow event matrix is intentionally narrow:

| Event | `build` | `docker-validation` | `container-security` | `deploy-pages` |
| --- | --- | --- | --- | --- |
| Pull request | Yes | Yes | Yes | No |
| Push to `master` | Yes | Yes | Yes | Yes, after `build` |
| Weekly schedule | Yes | No | Yes | No |
| Manual `workflow_dispatch` | Yes | No | Yes | No |

The scheduled run starts every Monday at `04:23 UTC` (`23 4 * * 1`). Maintainers can also select **CI** under the repository's **Actions** tab and use **Run workflow**; the scheduled container job explicitly checks out `master`; manual dispatch retains its selected workflow ref. These runs rebuild all three CI-local images with `--pull` and never publish them. Recurring scans matter because vulnerability intelligence and upstream base images change without a repository commit: a new policy-violating HIGH or CRITICAL finding is therefore detected by the next run.

Scheduled CI also regenerates Maven build-tool evidence against protected `master` and refreshes its advisory scan in the existing `build` job. Manual CI uses its selected workflow ref, allowing exact-head verification of an existing proposal; its container-security job uses the same selected workflow ref. Both build modes perform one verification lifecycle before generating build-tool evidence.

The external container tools and scan input are immutable while retaining readable source versions:

- Docker Hub, Hadolint `v2.14.0` scratch: `docker.io/hadolint/hadolint:v2.14.0@sha256:27086352fd5e1907ea2b934eb1023f217c5ae087992eb59fde121dce9c9ff21e` (`linux/amd64`; resolved child retained in auxiliary evidence)
- GHCR, Trivy `0.75.0`: `ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa` (`linux/amd64`)
- Docker Hub, Go `1.26.9-alpine3.24`: `docker.io/library/golang:1.26.9-alpine3.24@sha256:cdfd4fe2da6b225d8b40c6b7a105736e548e83ff56d5d8f9394446eeb5eb84e0` (`linux/amd64`; resolved child retained in auxiliary evidence)
- Docker Hub, Alpine `3.20`: `docker.io/library/alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc` (`linux/amd64`)

The supplied Docker Hub references are multi-platform OCI index digests, and CI explicitly pulls and inspects their `linux/amd64` images. OCI digests are registry- and repository-scoped, so changing only the registry while reusing a digest does not produce a valid reference. Before linting or scanning, CI verifies manifest availability, the locally resolved platform, and each tool's version or release identity. For gosu source, tag `1.19` is the human-readable upstream release identity and full commit `6456aaa0f3c854d199d0f037f068eb97515b7513` (`Update to 1.19`) is the immutable security identity. CI shallow-clones the tag, verifies its peeled commit and `HEAD`, and fails before govulncheck and policy enforcement if either differs.

Dependabot checks Docker dependencies weekly in `/` and `/docker/postgres`. This covers both Eclipse Temurin stages in the application Dockerfile and the PostgreSQL 18 Alpine base in the PostgreSQL Dockerfile. Updates are proposed for review with the `build(deps)` prefix and are never merged automatically. The root Docker configuration ignores only semantic-major updates of `eclipse-temurin`; it does not affect the separate PostgreSQL image, Maven dependencies, or GitHub Actions.

## Maven build-bootstrap integrity

The repository uses Maven Wrapper 3.3.4 in `only-script` mode to download Maven 3.10.0. The wrapper properties pin SHA-256 `1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff` for exactly `apache-maven-3.10.0-bin.zip`; the checked-in Unix and Windows wrapper scripts verify that digest before extracting or executing a newly downloaded distribution. CI validates that the checksum is present, is a 64-character hexadecimal value, the distribution URL uses HTTPS, and the reviewed archive and digest remain paired. Docker builds, CI, CodeQL, local development, and release verification all use the checked-in wrapper and therefore inherit this control without a separate download or build. The application Docker builder installs `unzip` before its first wrapper invocation so the Unix wrapper uses the configured ZIP; repository-owned consumers must not silently substitute the TAR.GZ distribution because it has different bytes and therefore a different digest.

Apache publishes the release archive, detached OpenPGP signature, and SHA-512 checksum at `https://downloads.apache.org/maven/maven-3/3.10.0/binaries/`, with release keys at `https://downloads.apache.org/maven/KEYS`. The configured ZIP was accepted only after its computed SHA-512 matched Apache's published checksum and its detached signature verified against the published keys; the SHA-256 pin above was then computed independently from those exact verified bytes. A future Maven upgrade must repeat this authoritative verification and atomically update the wrapper archive and digest, the focused policy expectation and tests, and this evidence before the new distribution is executed in trusted automation.

Maven Enforcer separately requires Java in `[21,22)` and Maven in `[3.9.15,)`; it checks build-tool compatibility after Maven starts, while the wrapper checksum protects the downloaded Maven archive before execution. This checksum is an integrity pin for the expected Maven distribution, not package signing or end-to-end provenance. It does not establish Apache account or release-process integrity, Maven plugin or dependency integrity, Maven Central availability, reproducible application bytecode, or vulnerability safety. Dependency and container vulnerability controls remain separate concerns.

## Maven build-tool advisory boundary

The `build` job owns a dedicated build-tool scan using the same pinned Trivy image and database authority as the image scans. It runs on ordinary pull requests, protected-master pushes, the weekly CI schedule, and manual CI dispatch. Pull-request checkout uses the event's exact head SHA; scheduled checkout uses `master`. Workflow permissions remain `contents: read`, with no dependency submission, privileged pull-request event, secrets, or persisted checkout credentials.

`scripts/build-tool-inventory.py` compiles a repository-owned Maven EventSpy and attaches it to the job's existing `clean verify`. The collector writes effective-model roots, authoritative Maven resolver identities, configured plugin descriptors, filtered classloader URLs, and successful goal executions. Its `observe-commands` operation discovers Maven goals in repository workflows, Dockerfile commands, and shell scripts, then observes goals whose complete plugin realms have not already executed. This currently adds only Docker's `dependency:go-offline`, without another verification lifecycle. Plugin goal prefixes come from matching embedded descriptors, never filenames. Collection also checks discovered commands against actual realm receipts; an added command cannot silently pass on old evidence. Dynamic goals and additional profiles fail closed pending separate authoritative evidence. The actual compiler and test-fork logs establish annotation processor paths, selected providers, booter artifacts, and Java agents. No Maven coordinate is derived from a filename.

The reviewed schema-2 contract is `.github/security/build-tool-inventory.json`. Boundary A retains twelve effective plugin roots, the directly invoked Dependency Plugin, and every resolved descriptor dependency. Install, Deploy, and Site currently have available realms without executed goals. All available realm members enter the complete SBOM; dependencies excluded in favor of Maven's own API retain inventory provenance, require reviewed distribution group/artifact authority, and are represented by the actual distribution versions in the SBOM. Configured, resolved, realm-available, and goal-executed are distinct states.

Boundary B blocks HIGH/CRITICAL findings in the mechanically proven authoritative execution set. `execution-scope.json` derives ownership from matching `MojoStarted`/`MojoSucceeded` records, complete actual realms, and the independent Maven debug goal plan, plus actual processor/provider/booter/agent paths and Maven distribution ownership. It currently contains 211 executable PURLs and 55 resolved-only PURLs, partitioning all 266 input identities. Ten plugin roots execute: nine during verification plus Dependency in Docker's direct command. The scanner re-collects the raw receipts and requires exact agreement with the generated scope before evaluating advisories. A manually reduced or stale subset fails.

The contract reviews dependency graphs, not a frozen execution allowlist. Binding or directly invoking an already inventoried plugin automatically promotes its entire observed realm into blocking ownership; no contract edit or package/CVE exception is needed. A shared package blocks if any authoritative executed owner uses it. New dependency graphs still require inventory review. The collector's deliberate `setupPluginRealm` resolves/registers available components without invoking their goals; only actual goal receipts grant plugin execution ownership. This is goal-level evidence, not bytecode-level reachability analysis or a claim about every class initializer.

The ownership boundary also includes both explicit processor paths, the actual Surefire/Failsafe JUnit Platform providers and booters, JaCoCo's runtime-classifier agent, and Mockito's explicit agent. Ordinary application/test classpaths remain under Dependency Review and existing application evidence. All 56 Maven distribution/bootstrap JARs are byte-checked against reviewed identities resolved from Apache's published distribution POM. JARs lacking embedded metadata use that authoritative resolution plus byte equality; unknown identities fail. The Maven distribution itself additionally has its authoritative `org.apache.maven:apache-maven:pom:3.10.0` PURL to cover advisories attached to that aggregate package. This package denotes the distribution, rather than an executing POM file.

The ephemeral CycloneDX 1.5 JSON artifact is `enterprise-shop-build-tools.cdx.json`, with root identity `enterprise-shop-build-tools`. Its complete, deterministic component list is checked against the contract before scanning. Material classifiers are encoded in Maven PURLs. Generated inventories, SBOMs, reports, effective models, and raw execution logs are uploaded as `build-tool-security-evidence`, including on failure; they are not committed. The versioned contract is a reviewed expectation, not a substitute for fresh execution evidence.

The scanner first requires a successful database update and then runs:

```bash
trivy sbom --skip-db-update --ignorefile /dev/null --list-all-pkgs \
  --exit-code 0 --format json --output enterprise-shop-build-tools.trivy.json \
  enterprise-shop-build-tools.cdx.json
```

The actual invocation runs inside the immutable Trivy image documented above. Exit status zero produces raw evidence; scanner/database errors still fail. `scripts/validate-build-tool-vulnerabilities.py` requires every input PURL and exact name/version in Trivy's readback and partitions HIGH/CRITICAL matches by validated execution ownership. `policy-result.json` preserves both `blocked` and `resolved_only` findings. Only `blocked` determines the advisory gate status. There are no CVE/package exceptions or inherited image/gosu exceptions. Unknown/omitted packages, malformed/truncated evidence, graph changes, missing starts/successes/plans/realms, and stale results fail before policy success. Isolated BeanUtils fixtures prove vulnerable HIGH detection, patched success, malformed input failure, and missing-database failure using the real pinned scanner.

Current Jetty HIGH findings remain visible in the full raw report and `resolved_only` evidence because the current Site realm has no authoritative executed goal. Success means **no blocking HIGH/CRITICAL findings in the authoritative execution set**, rather than no vulnerabilities. An isolated local `dependency:go-offline site:help` probe with the same contract promoted all Site realm members and blocked both Jetty findings; it is not a required repository command or a permanent Site exception.

Local reproduction requires a complete Java 21 JDK and Docker. From the repository root:

```bash
mkdir -p .tmp/build-tool-security
./mvnw -v > .tmp/build-tool-security/maven-version.txt
python scripts/build-tool-inventory.py prepare
./mvnw -B -X \
  -Dmaven.ext.class.path="$PWD/.tmp/build-tool-security/collector.jar" \
  -Dbuildtools.evidence="$PWD/.tmp/build-tool-security/verify.tsv" \
  -Dbuildtools.mode=verify clean verify > .tmp/build-tool-security/verify.log 2>&1
python scripts/build-tool-inventory.py observe-commands
python scripts/build-tool-inventory.py collect
python scripts/build-tool-inventory.py validate-bom
python scripts/scan-build-tools.py
```

Run these in a shell with `set -euo pipefail`; any failed command stops the gate. A managed environment may supply `BUILD_TOOL_SCAN_CA_BUNDLE` for a public CA trust bundle and `TRIVY_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-db:2` for Trivy's standard database when its default mirror is unavailable. Neither changes the vulnerability authority or disables TLS validation.

When changing a plugin, processor, provider, or other reviewed dependency graph, collect fresh successful execution evidence and run `collect --review-contract .tmp/build-tool-security/candidate-contract.json`. This writes a candidate expectation for explicit comparison and review, removes previous scan-ready output, and does not emit a production SBOM. Review roots, every transitive, realm filtering, classifier identities, advisories, and compatibility before updating the maintained contract and rerunning normal collection. CI never accepts candidates automatically. Pure execution changes within an unchanged reviewed graph derive a new scope directly from receipts. New required Maven goals from those repository sources are automatically observed when their realm has not executed, and must supply complete collector/debug-plan evidence; resolving an available realm alone cannot substitute for an executed-goal receipt. A Maven distribution change additionally requires the authoritative archive verification described above and a new POM-resolution/byte-match inventory.

This boundary covers the current candidate's reviewed Maven-package categories for the observed single-project Linux Java 21 verification and direct Docker Maven commands. Docker/restore `package`, protected-base OpenAPI comparison, CodeQL `clean verify`, and the development Spring Boot command were audited separately for additional plugin roots; they introduce no Site/Install/Deploy execution. Different project inputs and profiles require separate evidence. Discovery covers literal commands in the root Dockerfile, workflow YAML, and repository shell scripts; commands constructed dynamically or introduced through other languages/external tooling require extending discovery and instrumentation. Declared goals alone never establish execution ownership. Changed bootstrap configuration, build extensions, Maven versions, fork topology, or log formats require review and otherwise fail. Builder OS/JDK/native-package advisory coverage is maintained separately below. Wrapper shell logic, arbitrary external downloads, class-initializer reachability, and other execution graphs remain distinct limits of the Maven build-tool control. Wrapper integrity, dependency review, final-image Trivy, Actions pins, CodeQL, and behavioral tests retain separate ownership. Advisory matches establish affected versions, not goal-specific exploitability. Current findings and proof are recorded in [the build-tool audit](../testing/build-tool-advisory-audit.md).

## Docker builder OS, native-package, and JDK boundary

The root Dockerfile has two reviewed security boundaries: `eclipse-temurin:21-jdk-jammy AS builder` and `eclipse-temurin:21-jre-jammy AS runtime`. The builder installs `unzip`, runs the checksum-pinned Maven Wrapper, and executes `dependency:go-offline` and `package` with the existing Maven cache mount. The runtime copies the packaged application into a separate JRE image. A clean final runtime scan cannot establish builder security.

The existing `container-security` job exports an ephemeral `enterprise-shop/builder:ci` using:

```bash
mkdir -p .tmp/container-security/builder
export BUILDX_METADATA_PROVENANCE=max
docker buildx build --pull --platform linux/amd64 --target builder --load \
  --tag enterprise-shop/builder:ci \
  --metadata-file .tmp/container-security/builder/builder-build.json .
```

It then exports the normal runtime image using the same BuildKit builder and local cache. This requires an additional target export and image load, not a deliberately separate full Maven build: unchanged builder instructions and the Maven cache are reused for the runtime export. Both exports resolve bases with `--pull`. Maximum BuildKit metadata preserves the actual base material digests, platform, target, and image configuration digest; the policy checks the recorded base `image.resolvemode` is `pull` and that both exports used the same JDK base. Separate tags and artifacts keep the builder out of production output. Additional root Dockerfile stages or changed stage identities fail pending an explicit policy decision, including new artifact-producing stages.

PR checkout uses the exact event head SHA; scheduled checkout uses protected `master`; manual dispatch uses its selected ref. A recorded source SHA must match checkout and the event SHA. The same Monday `04:23 UTC` schedule rebuilds and rescans the builder alongside existing final-image checks. Before this change reaches protected master, the schedule is established by workflow/ref/provenance regression tests, not a claim that a scheduled builder run has already executed. Permissions remain read-only, checkout credentials are not persisted, and no secrets or privileged pull-request event are used.

`scripts/record-docker-builder.sh` records image IDs, JDK/JRE release files, Java properties, builder `javac`, Ubuntu identities, complete installed dpkg inventories, and JDK file paths for both application stages. `scripts/scan-docker-builder.sh` uses the existing pinned Trivy `0.75.0` digest and shared reviewed database/cache directory. It refreshes the vulnerability database, scans the builder with `--list-all-pkgs`, `--ignorefile /dev/null`, and no severity filter, and generates a separate CycloneDX 1.7 SBOM. Database, raw-scan, and SBOM exit statuses are retained independently. Scanner failures cannot be converted to empty clean reports.

The `docker-builder-security-evidence` artifact is uploaded with `always()` before policy enforcement and retained for fourteen days, including on vulnerability failure. It contains raw Trivy JSON, `builder.cdx.json`, build/image metadata, source/platform/Java/OS/dpkg comparison evidence, scanner statuses, and the JDK advisory input/provenance described below. A second `docker-builder-policy-result` artifact preserves the policy decision when valid evidence produces one. Missing or malformed evidence fails rather than synthesizing a success artifact.

`scripts/validate-builder-security.py evidence` requires the raw report and SBOM to identify the same built builder image and to represent every installed dpkg package, including the exact `unzip` version. Debian epochs and releases are retained when comparing Trivy's split version fields with dpkg and CycloneDX. Every HIGH or CRITICAL finding in the builder's recognized OS/library contents blocks, including unfixed findings. There are no builder exceptions, package wildcards, severity reductions, image-wide exclusions, or inherited runtime/PostgreSQL/gosu suppressions. Lower-severity findings remain in the unfiltered raw evidence.

### Tar-installed Temurin JDK advisory authority

The pinned Trivy image demonstrably does not inventory the Temurin JDK itself: this JDK is installed from an upstream tar archive under `/opt/java/openjdk`, rather than dpkg. The live builder raw report has Ubuntu and Java JAR results but no Temurin/OpenJDK distribution package; its CycloneDX likewise omits that distribution. Trivy's [v0.72.0 Java analyzer](https://github.com/aquasecurity/trivy/blob/v0.72.0/pkg/fanal/analyzer/language/java/jar/jar.go) recognizes JAR/WAR/EAR/PAR artifacts, not the JDK release file or JMOD/native runtime. Trivy's Java index is a JAR identity index, not JDK advisory coverage. The raw Trivy-generated SBOM is preserved without inventing a scanner-recognized JDK package.

To close this specific limitation, `fetch-jdk` retrieves the latest published [Adoptium Temurin Vulnerability Disclosure Report](https://github.com/adoptium/temurin-vdr-generator#releases) on every run, without installing another scanner. The vendor aggregates OpenJDK Vulnerability Group advisories with NVD ratings into CycloneDX 1.4. The repository records the release tag, release/asset IDs, publication timestamp, exact SHA-256 and source URL; it validates the vendor checksum manifest and GitHub asset digest when available. Checksums bind the retained bytes, not a signature or proof that the vendor release account is uncompromised. Upstream release immutability is recorded and is not presumed.

The gate binds the observed Eclipse Adoptium `JAVA_VERSION` to actual builder `javac`, compares affected versions within the Java 21 feature line, and applies HIGH/CRITICAL thresholds using the maximum published CVSS score (7.0/9.0). Adoptium's `vers:generic` entries encode OJVG's affected version and earlier within each feature line; they are not interpreted as exact-version-only matches. Repeated CVE records combine their published bounds and ratings; an incomplete duplicate requires a complete record with the same advisory description, and cannot remove its applicability or severity. Unknown version semantics, missing complete applicability, malformed scores, changed vendor, missing/tampered evidence, or failed retrieval fail closed. No CVE-specific JDK exception or reachability exclusion exists. This authority depends on the vendor's advisory completeness and publication cadence; a successfully fetched latest report does not independently prove that the vendor has published every new advisory.

The JDK release/Java/compiler files and vendor VDR are distinct from Trivy's recognized-package SBOM. The builder/runtime file comparison identifies compiler tools, JMODs and native libraries absent from the JRE, without claiming each is executed. The JDK gate evaluates the Docker builder distribution as a whole; it does not exempt a vulnerable component merely because a particular Maven goal appears unlikely to call it.

### Inventory and execution ownership

The maintained container policy scans installed recognized packages. It does not establish binary-level reachability. Source audit shows Docker RUN instructions invoke the shell, `apt-get`, `rm`, `chmod`, and the Maven Wrapper; the wrapper uses shell/coreutils operations, verifies SHA-256, and chooses available download tooling before ZIP extraction. Cold wrapper provisioning uses `unzip`; a warm Maven cache can avoid downloading/extracting the distribution. Presence of inherited `wget`, `curl`, binutils, JDK utilities, or a shared library is inventory evidence, not proof each ran. No hand-maintained executable allowlist is needed for an inventory-wide vulnerability threshold.

The Maven distribution and resolved plugin/processor/test tooling primarily reside in the `/root/.m2` BuildKit cache mount, which is not exported as builder image filesystem contents. The builder still contains the packaged application and recognized Java libraries, so some package identities overlap PR #458's independent build-tool evidence. The audited scan overlaps 22 of its 266 Maven PURLs, including the packaged Hibernate processor and shared dependencies; it does not duplicate or replace the complete build-tool inventory or its execution-derived ownership. The build-tool policy and its resolved-only advisories remain unchanged.

| Environment/component | Repository evidence and ownership |
| --- | --- |
| GitHub-hosted Temurin 21 Maven and CodeQL builds | Java feature/version setup, Maven Wrapper integrity, build-tool execution/advisory evidence, tests and coverage; hosted runner OS/JDK provisioning is a GitHub/Adoptium trust boundary. Docker evidence does not attest those JDK bytes. |
| Application Docker builder | Fresh-base BuildKit provenance, installed OS/native packages and recognized Java libraries through Trivy, separate measured Temurin JDK identity and vendor advisory policy. |
| Final application runtime | Existing independent JRE-image Trivy HIGH/CRITICAL policy and CycloneDX; runtime user, image identity and application behavior remain unchanged. |
| Custom PostgreSQL image | One stage; existing final-image policy, SBOM and scoped gosu applicability evidence. Its build adds dictionary files and targeted package repairs; it has no separate discarded builder. |
| Compose database-role bootstrap | Executes the repository shell script inside the same custom PostgreSQL image, not a new image or application builder boundary. |
| Immutable Trivy/Hadolint tool images | Separate auxiliary policy below; builder evidence does not establish scanner integrity or Haskell executable advisory coverage. |
| Immutable Go govulncheck image | Separate auxiliary OS/Go policy below; installed gosu compiler identity remains distinct from the analysis compiler. |
| Immutable Alpine policy-test image | Scanner-policy fixture, not an application artifact-producing builder. |
| Dockerfile frontend, BuildKit and Docker daemon | Build execution infrastructure; recorded frontend/base materials do not constitute vulnerability or source-analysis coverage for all build infrastructure. |

Hosted runners, arbitrary downloads, vendor/scanner publication completeness, and dynamically constructed external commands remain separate limits. Auxiliary ownership is described below. This policy does not claim complete software-supply-chain coverage.

### Builder OpenSSL remediation

The first fresh builder scan found HIGH `CVE-2026-84782` in `libssl3 3.0.2-0ubuntu1.29`; the runtime stage's existing targeted installation already resolves the fixed `3.0.2-0ubuntu1.30`. The builder now adds only `libssl3` alongside its required `unzip` installation. It receives the maintained Jammy candidate during fresh builds without a blanket OS upgrade, base/JDK change, package-version freeze, or exception. The Wrapper policy's reviewed installation line is adjusted only for this compatible addition; Maven archive/version/checksum expectations are unchanged.

Focused offline regression coverage:

```bash
python -m unittest discover -s scripts/tests -p 'test_builder_security.py'
```

Local reproduction requires Docker and the pinned scanner variable above. Create the builder/runtime images with maximum BuildKit metadata using the workflow commands, set `EXPECTED_SOURCE_SHA` to the checkout SHA, then run `record-docker-builder.sh`, `scan-docker-builder.sh`, `validate-builder-security.py fetch-jdk`, and `validate-builder-security.py evidence`. The pipeline intentionally uploads collected evidence before the final gate; local users should retain that directory on failure. Ordinary Maven/CodeQL/PostgreSQL verification remains mandatory independently of these image checks.

## Java platform baseline

Java 21 LTS is the application compilation and runtime baseline. Maven compiles Java 21 source to Java 21 bytecode, GitHub Actions installs Temurin 21, and the application Dockerfile uses a Temurin 21 JDK builder and Temurin 21 JRE runtime. Maven Enforcer admits only JDK versions in `[21,22)`, so a build fails early if any build environment drifts to another Java feature release. After the security job builds the final application image, CI also runs `java` inside that image, prints its version information, and requires `java.specification.version` to equal `21` before scanning it.

Dependabot semantic-major updates for the root `eclipse-temurin` dependency are ignored to prevent an automated Docker-only change from breaking this deliberately aligned platform. This rule does not freeze the image: fresh builds still pull the current Java 21 tags, supported updates within the Java 21 line remain eligible for Dependabot, and the existing Trivy pipeline continues to inspect the resulting runtime image.

A future Java feature-release upgrade, including Java 25, is not permanently rejected. It must instead be proposed in an ADR or dedicated migration pull request that updates the whole platform coherently. Before adoption, that change must demonstrate application and dependency compatibility, run the full automated test and container-security suites, evaluate performance against the Java 21 baseline, and document a rollback to the previous Java 21 build and runtime images. A Java migration must not be accepted as an automatic two-line base-image update.

## Dockerfile linting policy

Hadolint enforces Dockerfile correctness and maintainability rules for both Dockerfiles. The CI command ignores only `DL3008` because the application runtime image intentionally receives security fixes from the maintained Ubuntu package repositories during image rebuilds instead of pinning a stale exact `apt` package version in source.

Local reproduction:

```bash
docker run --rm --platform linux/amd64 \
  -v "${PWD}:/workspace:ro" \
  -w /workspace \
  docker.io/hadolint/hadolint:v2.14.0@sha256:27086352fd5e1907ea2b934eb1023f217c5ae087992eb59fde121dce9c9ff21e \
  hadolint --ignore DL3008 Dockerfile docker/postgres/Dockerfile
```

Validate that Trivy itself can load the repository ignore policy before expensive image builds:

```bash
mkdir -p .tmp/container-security/trivy-cache

docker run --rm \
  -v "${PWD}/.trivyignore.yaml:/workspace/.trivyignore.yaml:ro" \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -w /workspace \
  ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa image --scanners vuln --severity CRITICAL --exit-code 0 --format table --ignorefile .trivyignore.yaml docker.io/library/alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
```

## Vulnerability scanning policy

Trivy scans both final images with the `vuln` scanner. The policy is:

- Every application-image `HIGH` or `CRITICAL` vulnerability fails CI after raw reports and SBOMs are uploaded.
- PostgreSQL-image `HIGH` findings pass only when every finding is the `stdlib` component at `usr/local/bin/gosu`. Any HIGH in Alpine, PostgreSQL, another package, or another target fails CI.
- The gosu source identity and upstream `govulncheck` steps are blocking and run before the component-aware PostgreSQL check. The path/package allowance is therefore valid only while that reachability check succeeds.
- PostgreSQL `CRITICAL` vulnerabilities remain subject to the exact, time-bounded `.trivyignore.yaml` exception described below; all other CRITICAL findings fail CI.
- Unfixed vulnerabilities are not ignored by default.
- Individual CVEs must not be silently suppressed.
- Raw scanner reports are evidence of everything Trivy detected; policy scans are the actionable gate after documented applicability analysis.
- Exceptions are not vulnerability fixes. An expired exception must be removed, renewed with fresh evidence, or replaced by a remediation before the expiry date. Trivy `0.75.0` requires `expired_at` in `.trivyignore.yaml` to be an RFC 3339 timestamp, so the configuration uses the end of the UTC calendar day.

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
  ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa image --scanners vuln --severity HIGH,CRITICAL --exit-code 0 --format json --output /reports/enterprise-shop-app-trivy-raw.json enterprise-shop/app:ci

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -v "${PWD}/.tmp/container-security/reports:/reports" \
  ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa image --scanners vuln --severity HIGH,CRITICAL --exit-code 0 --format json --output /reports/enterprise-shop-postgres-trivy-raw.json enterprise-shop/postgres:ci
```

Validate the unfiltered JSON evidence and run the final policy scans:

```bash
python -m unittest discover -s scripts/tests -p 'test_*.py'
python scripts/validate-container-vulnerability-policy.py application .tmp/container-security/reports/enterprise-shop-app-trivy-raw.json
python scripts/validate-container-vulnerability-policy.py postgres .tmp/container-security/reports/enterprise-shop-postgres-trivy-raw.json

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa image --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format table enterprise-shop/app:ci

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -v "${PWD}/.trivyignore.yaml:/workspace/.trivyignore.yaml:ro" \
  -w /workspace \
  ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa image --scanners vuln --severity CRITICAL --exit-code 1 --format table --ignorefile .trivyignore.yaml --show-suppressed enterprise-shop/postgres:ci
```

Use `--show-suppressed` on the PostgreSQL policy scan so reviewers can see when the scoped gosu exception was applied.

## pgJDBC CVE-2026-54291 remediation

CI run #490 detected `CVE-2026-54291` in `postgresql-42.7.11.jar`. The pgJDBC advisory identifies 42.7.11 as affected and 42.7.12 as the first fixed release for the SCRAM-SHA-256-PLUS channel-binding downgrade when `channelBinding=require` is used. Enterprise Shop's repository-controlled JDBC URLs do not set `channelBinding=require` (or another explicit channel-binding mode), so the vulnerable option is not enabled by the checked-in runtime configuration. Deployment operators can supply `DATABASE_URL`, however, and version-only scanners cannot establish the effective runtime connection options. The driver is therefore updated rather than suppressed.

Spring Boot 4.1.0 dependency management supplied pgJDBC 42.7.11 through its `postgresql.version` property. Enterprise Shop overrides that supported property to 42.7.12; it retains the existing runtime dependency declaration and does not add a duplicate dependency or change the Spring Boot line. This patch-only remediation does not change JDBC URLs, database identities, PostgreSQL server behavior, Flyway, or persistence mappings.

## Application OpenSSL CVE-2026-84782 remediation

Protected-master CI run #1075 found HIGH `CVE-2026-84782` in `libssl3` and `openssl` version `3.0.2-0ubuntu1.29`, inherited from the current `eclipse-temurin:21-jre-jammy` runtime image. Ubuntu Jammy security repositories provide fixed version `3.0.2-0ubuntu1.30`, so the runtime package-install layer explicitly includes `libssl3` and `openssl` alongside `curl`. This updates only the affected packages to the current maintained Jammy candidates during image rebuilds; it does not pin a version that would prevent later security updates or perform a broad operating-system upgrade.

## PostgreSQL c-ares CVE-2026-33630 remediation

CI run #496 found HIGH `CVE-2026-33630` in `c-ares 1.34.5-r0`, inherited by the PostgreSQL 18 Alpine image. Because Alpine provided the fixed `1.34.6-r0` package, the PostgreSQL Dockerfile applies a targeted `apk upgrade --no-cache c-ares` to the final image. No policy exception was added; CI rebuilds and rescans the final image through the existing raw-report, SBOM, and blocking-policy workflow.

## gosu CVE-2025-68121 triage

CI run #481 failed only at PostgreSQL CRITICAL policy enforcement because Trivy detected `CVE-2025-68121` in the Go standard library metadata for `usr/local/bin/gosu`, inherited from the official PostgreSQL Alpine image then in use. The Enterprise Shop PostgreSQL Dockerfile only copies Polish full-text-search dictionary files into that base image.

The official gosu security policy says generic binary scanners can report Go CVEs for packages that gosu never invokes and asks reporters to validate reachability with `govulncheck-with-excludes.sh`. gosu `1.19` source imports `os`, `os/exec`, `runtime`, `syscall`, `github.com/moby/sys/user`, and `golang.org/x/sys/unix`; it does not import or call `crypto/tls`. The CI job therefore runs the upstream gosu `1.19` govulncheck wrapper before applying the exception.

The repository-level `.trivyignore.yaml` contains one path-scoped exception:

- ID: `CVE-2025-68121`
- Path: `usr/local/bin/gosu`
- Expiry: `2026-10-31` (`expired_at: "2026-10-31T23:59:59Z"`)
- Reason: upstream gosu govulncheck analysis classifies the affected `crypto/tls` TLS session-resumption certificate-validation path as unreachable from gosu `1.19`.

No package-wide, image-wide, wildcard, unfixed, or blanket Go standard-library suppression is configured. A different CRITICAL finding in gosu, PostgreSQL, Alpine, the Java runtime, or the application remains outside this exception and fails CI. Scheduled scans keep the CVE visible in the unfiltered raw report, and the policy gate fails once the exception expires. The `2026-10-31` deadline is not automatically extended; changing it requires a reviewed source change supported by fresh evidence.

The same evidence-first rule applies to gosu HIGH findings: Trivy reports vulnerabilities from the Go version and package metadata embedded in the inherited binary, while `govulncheck` analyzes whether vulnerable symbols are reachable from gosu. The raw findings remain visible because a successful reachability analysis is contextual risk evidence, not a patched binary. A newly reachable result, changed source identity, non-`stdlib` package, or target other than `usr/local/bin/gosu` fails the workflow instead of being ignored.

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

# CI additionally binds GOVERSION to the installed gosu binary; see the auxiliary policy below.
GOLANG_IMAGE=docker.io/library/golang:1.26.9-alpine3.24@sha256:cdfd4fe2da6b225d8b40c6b7a105736e548e83ff56d5d8f9394446eeb5eb84e0 ./govulncheck-with-excludes.sh ./...
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
  ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa image --format cyclonedx --output /sbom/enterprise-shop-app.cdx.json enterprise-shop/app:ci

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -v "${PWD}/.tmp/container-security/sbom:/sbom" \
  ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa image --format cyclonedx --output /sbom/enterprise-shop-postgres.cdx.json enterprise-shop/postgres:ci

python -m json.tool .tmp/container-security/sbom/enterprise-shop-app.cdx.json >/dev/null
python -m json.tool .tmp/container-security/sbom/enterprise-shop-postgres.cdx.json >/dev/null
```

## GitHub Actions provenance

GitHub Action release tags and major-version aliases can be moved by their repository owner, so CI executes external actions by their immutable full commit SHA. An adjacent release-version comment keeps each dependency reviewable and gives Dependabot's existing `github-actions` updater the release identity it needs to propose SHA and comment updates; updates remain subject to normal review and are not auto-merged.

All repository checkouts are read-only and explicitly set `persist-credentials: false`, preventing the workflow token from remaining in Git configuration after checkout. The Pages deployment continues to use only its narrowly scoped job permissions and does not require a Git push.

Run the provenance policy and its tests locally before changing a workflow:

```bash
python scripts/validate-github-actions-policy.py
python -m unittest scripts.tests.test_validate_github_actions_policy
```

The local validator enforces reference shape, checkout policy and the reviewed CI/CodeQL configuration boundaries; it cannot prove that a commit exists in the named repository or matches the adjacent release comment. Verify the official tag-to-commit mapping separately. Successful GitHub job preparation confirms that the repository can resolve the referenced commit, but does not independently validate the version comment.

Before accepting an action update, resolve the intended release in the official repository and verify both the tag and commit object, for example:

```bash
git ls-remote https://github.com/actions/checkout.git refs/tags/v7.0.0
# Clone the official repository when the tag is annotated, then compare its peeled commit:
git rev-parse 'refs/tags/v7.0.0^{commit}'
```

Use the resulting 40-character lowercase commit SHA in `uses:` and retain the exact release tag in the comment. Never copy a commit from a fork.
