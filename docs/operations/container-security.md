# Container supply-chain security validation

CI validates the repository Dockerfiles, the application Docker builder stage, and the final local images used for the Enterprise Shop application and custom PostgreSQL database. These checks add supply-chain visibility without publishing images or changing runtime application/database behavior.

## CI architecture

The `container-security` job is separate from Maven verification and functional Docker validation so failures are easy to classify:

- Hadolint checks the root `Dockerfile` and `docker/postgres/Dockerfile`.
- Docker builds CI-local images from fresh bases with `--pull`, tagged `enterprise-shop/builder:ci`, `enterprise-shop/app:ci` and `enterprise-shop/postgres:ci`. Builder/runtime exports preserve BuildKit base and image provenance.
- Trivy `0.72.0` scans each final image for operating-system and application/library vulnerabilities and reuses a GitHub Actions cache for the scanner database.
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

- Docker Hub, Hadolint `v2.14.0-alpine`: `docker.io/hadolint/hadolint:v2.14.0-alpine@sha256:7aba693c1442eb31c0b015c129697cb3b6cb7da589d85c7562f9deb435a6657c` (`linux/amd64` child manifest `sha256:be27962427a85de242820cb710a374478cce9bfb534a2c07e4fa54741d98908f`)
- GHCR, Trivy `0.72.0`: `ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f` (`linux/amd64`)
- Docker Hub, Go `1.25.7-bookworm`: `docker.io/library/golang:1.25.7-bookworm@sha256:564e366a28ad1d70f460a2b97d1d299a562f08707eb0ecb24b659e5bd6c108e1` (`linux/amd64` child manifest `sha256:58259daf0a27c150118663ef7452aa94d66a86d55e73b3443386146623f5364d`)
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

`scripts/record-docker-builder.sh` records image IDs, JDK/JRE release files, Java properties, builder `javac`, Ubuntu identities, complete installed dpkg inventories, and JDK file paths for both application stages. `scripts/scan-docker-builder.sh` uses the existing pinned Trivy `0.72.0` digest and shared reviewed database/cache directory. It refreshes the vulnerability database, scans the builder with `--list-all-pkgs`, `--ignorefile /dev/null`, and no severity filter, and generates a separate CycloneDX 1.7 SBOM. Database, raw-scan, and SBOM exit statuses are retained independently. Scanner failures cannot be converted to empty clean reports.

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
| Immutable Trivy/Hadolint tool images | Scanner/linter execution authority with pinned digest/platform/version checks; this builder policy does not advisory-scan their own installed contents. |
| Immutable Go govulncheck image | Runs gosu source/applicability analysis; its own Go/OS toolchain is a separate security-evidence-generator boundary. |
| Immutable Alpine policy-test image | Scanner-policy fixture, not an application artifact-producing builder. |
| Dockerfile frontend, BuildKit and Docker daemon | Build execution infrastructure; recorded frontend/base materials do not constitute vulnerability or source-analysis coverage for all build infrastructure. |

Hosted runners, arbitrary downloads, vendor/scanner publication completeness, dynamically constructed external commands, and auxiliary security-tool image contents remain separate limits. The next repository-owned inventory/advisory decision should address immutable security-tool images that execute analysis commands, beginning with the Go govulncheck toolchain. This task does not claim complete software-supply-chain coverage.

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

- Every application-image `HIGH` or `CRITICAL` vulnerability fails CI after raw reports and SBOMs are uploaded.
- PostgreSQL-image `HIGH` findings pass only when every finding is the `stdlib` component at `usr/local/bin/gosu`. Any HIGH in Alpine, PostgreSQL, another package, or another target fails CI.
- The gosu source identity and upstream `govulncheck` steps are blocking and run before the component-aware PostgreSQL check. The path/package allowance is therefore valid only while that reachability check succeeds.
- PostgreSQL `CRITICAL` vulnerabilities remain subject to the exact, time-bounded `.trivyignore.yaml` exception described below; all other CRITICAL findings fail CI.
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

Validate the unfiltered JSON evidence and run the final policy scans:

```bash
python -m unittest discover -s scripts/tests -p 'test_*.py'
python scripts/validate-container-vulnerability-policy.py application .tmp/container-security/reports/enterprise-shop-app-trivy-raw.json
python scripts/validate-container-vulnerability-policy.py postgres .tmp/container-security/reports/enterprise-shop-postgres-trivy-raw.json

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f image --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format table enterprise-shop/app:ci

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.tmp/container-security/trivy-cache:/root/.cache/trivy" \
  -v "${PWD}/.trivyignore.yaml:/workspace/.trivyignore.yaml:ro" \
  -w /workspace \
  ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f image --scanners vuln --severity CRITICAL --exit-code 1 --format table --ignorefile .trivyignore.yaml --show-suppressed enterprise-shop/postgres:ci
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

## GitHub Actions provenance

GitHub Action release tags and major-version aliases can be moved by their repository owner, so CI executes external actions by their immutable full commit SHA. An adjacent release-version comment keeps each dependency reviewable and gives Dependabot's existing `github-actions` updater the release identity it needs to propose SHA and comment updates; updates remain subject to normal review and are not auto-merged.

All repository checkouts are read-only and explicitly set `persist-credentials: false`, preventing the workflow token from remaining in Git configuration after checkout. The Pages deployment continues to use only its narrowly scoped job permissions and does not require a Git push.

Run the provenance policy and its tests locally before changing a workflow:

```bash
python scripts/validate-github-actions-policy.py
python -m unittest scripts.tests.test_validate_github_actions_policy
```

The local validator enforces reference shape and checkout policy only; it cannot prove that a commit exists in the named repository or matches the adjacent release comment. Verify the official tag-to-commit mapping separately. Successful GitHub job preparation confirms that the repository can resolve the referenced commit, but does not independently validate the version comment.

Before accepting an action update, resolve the intended release in the official repository and verify both the tag and commit object, for example:

```bash
git ls-remote https://github.com/actions/checkout.git refs/tags/v7.0.0
# Clone the official repository when the tag is annotated, then compare its peeled commit:
git rev-parse 'refs/tags/v7.0.0^{commit}'
```

Use the resulting 40-character lowercase commit SHA in `uses:` and retain the exact release tag in the comment. Never copy a commit from a fork.
