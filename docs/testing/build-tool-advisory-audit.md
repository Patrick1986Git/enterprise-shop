# Build-tool advisory coverage audit

PR #458 on `codex/build-tool-advisory-inventory` is the single continuation of this work. Its protected-master base is `bcba8c7f7b9bcae2fda58a6025ce4eb0f3b6eb00`; its starting PR HEAD was `2175900fecd95e665e2b015cb4f032e61b46ba96`. PR #457 remains merged and untouched. Its resulting base completed CI #1131 (run 37568924499), CodeQL #623 (run 37568924421, including successful actual `Analyze with CodeQL`), and JaCoCo ratchet #441 (run 37569227214, `ratchet=false`).

The unavailable local handoff commit `2d91c31fd9cbe481d320663d2632ec600f6c500a` never reached GitHub. The starting provenance commit established the existing draft without claiming SHA identity. Every implementation and repair belongs to this PR; no replacement branch/PR, merge, or auto-merge is authorized.

## Proven inventory boundary

The checked-in Maven Wrapper 3.3.4, in `only-script` mode, executes Maven 3.10.0 from the reviewed ZIP with SHA-256 `1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff`. Local resolution and successful complete verification used Temurin Java 21.0.12.1+1 LTS. The hosted workflow retains its existing Temurin Java 21 setup; the exact hosted patch version is recorded in each evidence artifact.

| Effective plugin | Version | Normal `clean verify` execution |
| --- | --- | --- |
| Spring Boot | 4.1.1 | Yes |
| Compiler | 3.16.0 | Yes |
| Surefire | 3.5.6 | Yes |
| Failsafe | 3.6.0 | Yes |
| JaCoCo | 0.8.15 | Yes |
| Enforcer | 3.6.3 | Yes |
| Clean | 3.5.0 | Yes |
| Resources | 3.5.0 | Yes |
| Jar | 3.5.1 | Yes |
| Install | 3.1.4 | No |
| Deploy | 3.1.4 | No |
| Site | 3.22.0 | No |

The Docker builder additionally invokes Dependency Plugin 3.10.0's `dependency:go-offline`. Install, Deploy, and Site are effective available realms, resolved without invoking their goals and deliberately included in the reviewed advisory boundary. Their presence is not a claim that those goals execute during verification.

A repository-owned EventSpy uses `MavenPluginManager` to resolve every effective descriptor and filtered class realm, records resolver coordinates against canonical artifact paths, and observes successful actual goals. Configured and executing realms must agree. Actual compiler and fork logs establish dynamically selected classpaths. Successful session/footer records, exact reviewed graph equality, and every execution-path identity are required. A plain POM declaration list or `dependency:resolve-plugins` union is insufficient: Maven excludes some descriptor dependencies and imports its own distribution APIs. Excluded candidates remain in the inventory, require reviewed distribution group/artifact authority, and are not scanned as though their excluded versions execute.

The final reviewed graph has 13 plugin roots, 200 unique resolved plugin artifacts (187 non-root artifacts), and 185 unique realm artifacts (172 non-root artifacts). Shared coordinates are deduplicated; counts are union counts rather than the sum across overlapping realms. The contract preserves complete per-root resolution and realm membership.

Both real compiler executions use MapStruct processor 1.6.3 and Hibernate processor 7.4.11.Final, plus 18 processor-path transitives: 20 processor components in total. Missing either required processor or any reviewed component fails before scanning. Hibernate's build-path version is kept distinct from the packaged artifact's version.

Surefire and Failsafe actually select `org.apache.maven.surefire.junitplatform.JUnitPlatformProvider`. Each provider path has six components: `surefire-junit-platform`, `common-java5`, `surefire-api`, `surefire-logger-api`, and `surefire-shared-utils`, respectively at 3.5.6 and 3.6.0, plus shared JUnit Platform Launcher 6.0.3. Each owned booter path also contains the corresponding `surefire-booter` and `surefire-extensions-spi`. Ordinary test classpath components are subtracted from booter ownership. Both test forks execute JaCoCo agent `0.8.15` with classifier `runtime` and Mockito Core `5.23.0` as explicit Java agents.

All 56 Maven distribution/bootstrap JARs byte-match artifacts resolved from the authoritative [Apache Maven 3.10.0 distribution POM](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0.pom), whose reviewed SHA-256 is `4a4f1c29b31031c6d37ab4eea0d93d2321fd1dfd313896bc6c94b7ce45f8e62c`. Five lack embedded `pom.properties`: AOP Alliance 1.0, ASM 9.10.1, Guice 5.1.0 with classifier `classes`, Javax Inject 1, and JSpecify 1.0.1. Their coordinates come from distribution resolution and byte equality, never filenames. The aggregate distribution PURL is additionally included as `pkg:maven/org.apache.maven/apache-maven@3.10.0?type=pom`; it denotes Maven rather than an executing POM. No Maven-package provenance is unknown within this reviewed boundary.

## CycloneDX and scanner evidence

The maintained contract `.github/security/build-tool-inventory.json` is a reviewed expectation. Fresh event, effective-model, and execution-path evidence must reproduce it; candidate updates are never accepted automatically. Generated output remains ephemeral. The production CycloneDX 1.5 artifact is `enterprise-shop-build-tools.cdx.json`, root identity `enterprise-shop-build-tools`, containing 266 unique Maven component/PURL identities. Its deterministic owned schema validates against the official CycloneDX 1.5 schema; input identity, all roots/components, uniqueness, classifier/type semantics, and absence of BeanUtils 1.9.4 are checked before scanning.

The existing immutable authority is:

`ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f`

The local tested vulnerability database reported `UpdatedAt=2026-10-07T07:38:55.515026687Z`. Its default GCR mirror was blocked by local network policy; Trivy's standard GHCR database repository succeeded with normal TLS verification. The failure was not classified as clean, and no second vulnerability authority was added.

The real pinned scanner's `sbom --skip-db-update --ignorefile /dev/null --list-all-pkgs --exit-code 0 --format json` path reads back all 266 identities with exact versions, including classifier-qualified and aggregate POM PURLs. Repository policy then blocks every HIGH/CRITICAL match. Raw scanner status and raw JSON remain independent evidence. No image/gosu exception applies.

Isolated committed fixtures prove:

- BeanUtils 1.9.4 yields `CVE-2025-48734`, severity HIGH, and is blocked by the repository policy.
- BeanUtils 1.11.0 yields no HIGH/CRITICAL finding and passes.
- Truncated CycloneDX returns nonzero scanner status.
- An empty database cache with updates disabled returns nonzero scanner status.

The vulnerable package exists only in fixture data. Focused offline regressions additionally reject new plugins/processors, missing roots/transitives, conflicting provenance, unresolved coordinates, malformed or truncated streams, partial/duplicate CycloneDX, omitted scanner packages, nonzero/error status, changed distribution bytes, and stale scan-ready outputs. Collector failures withhold the completion marker because Maven otherwise logs EventSpy exceptions and continues.

## Narrow advisory remediation and remaining block

The baseline's actually owned packages produced thirteen HIGH package/advisory matches, covering nine distinct CVEs. Build-plugin dependency overrides address only independently verified current advisories while retaining every plugin and platform version:

| Build-realm package | Remediation | Verified advisory |
| --- | --- | --- |
| Plexus Utils | 3.6.1 on the existing 3.x line; 4.0.3 on the existing 4.x line | CVE-2025-67030 / GHSA-6fmv-xxpf-w3cw |
| jsoup in Site | 1.23.2 | CVE-2026-75140 |
| Jackson Core in Boot plugin | 3.1.7 | CVE-2026-89407, CVE-2026-89425 |
| Jackson Databind in Boot plugin | 3.1.7 | CVE-2026-68497, CVE-2026-91776, CVE-2026-91777 |

BeanUtils 1.11.0 remains present in both Site and Dependency actual resolved realms; 1.9.4 is absent. Application dependency declarations/management, Java/Maven/Spring Boot, processor versions, application source, API, schema, migrations, Docker identities, CodeQL, compiler-warning policy, and JaCoCo baseline remain unchanged.

After remediation, the real production scan remains blocked by:

| Maven component | HIGH advisory | Scanner-reported fixed version |
| --- | --- | --- |
| `org.eclipse.jetty:jetty-http:9.4.58.v20250814` | CVE-2026-2332 | 9.4.60 |
| `org.eclipse.jetty:jetty-security:9.4.58.v20250814` | CVE-2026-10050 | 9.4.63 |

Both belong to the effective Site realm. Public Maven Central 9.4 metadata ends at 9.4.58.v20250814; the [Jetty vendor advisory](https://github.com/jetty/jetty.project/security/advisories/GHSA-2fvj-hgj9-j2gr) identifies patched 9.4.63. No compatible public fix was available during the audit. A Jetty major migration or commercial artifact acquisition is outside this narrow authorized remediation. The findings remain blocking, with no exception, goal exclusion, or threshold reduction. A version/advisory match does not prove exploitability through a particular Maven goal.

The compatible plugin overrides completed a local Java 21 `clean verify`: 1,151 Surefire tests and 325 Failsafe PostgreSQL/Testcontainers tests, no failures/errors/skips. Compiler-warning policy passed. JaCoCo remained exactly 3,958 covered / 342 missed lines and 1,079 covered / 194 missed branches, satisfying the unchanged baseline. These local results do not substitute for hosted exact-head verification. PR #458 must remain draft and cannot be declared squash-ready while its real build-tool gate is blocked.

## Ownership and remaining gaps

[Container-security documentation](../operations/container-security.md#maven-build-tool-advisory-boundary) owns reproduction commands, scan policy, PR/scheduled trust boundaries, and reviewed contract updates. [Testing strategy](strategy.md#maven-build-tool-security-tests) owns regression and collector compilation boundaries. The existing `build` job reuses one verification lifecycle and uploads `build-tool-security-evidence` even on failure; weekly protected-master and manual selected-ref regeneration use the same vulnerability authority and cache model.

Dependency Review/Dependabot retain ordinary application/development graph ownership; their build-tool visibility is not assumed. Final-image Trivy/CycloneDX owns packaged application/PostgreSQL contents. Wrapper source and distribution checksum own bootstrap integrity. This Maven SBOM owns reviewed Maven-package advisories, including distribution libraries and dynamic providers/agents. Actions pins/Dependabot own action version provenance, and unchanged CodeQL plus behavioral tests own normal repository source.

Remaining blind spots are Wrapper shell logic, builder JDK/operating-system/native-tool advisories, arbitrary external downloads, other platforms/profiles/multi-module execution, and goal-specific exploitability. The collector source asset is compiled separately with Java 21 `-Xlint:all -Werror`; successful normal Java/Kotlin CodeQL analysis does not establish separate collector/Python analysis. Current Trivy database coverage and severity classification remain advisory-authority limits. The next highest-value repository-owned coverage gap is builder-stage JDK/OS/native-tool inventory and advisory evidence; resolving the two known Jetty blockers takes precedence before merging this work.
