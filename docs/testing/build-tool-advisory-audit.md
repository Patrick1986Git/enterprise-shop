# Build-tool advisory coverage audit

PR #458 on `codex/build-tool-advisory-inventory` is the single continuation of this work. Its protected-master base is `bcba8c7f7b9bcae2fda58a6025ce4eb0f3b6eb00`; its starting PR HEAD was `2175900fecd95e665e2b015cb4f032e61b46ba96`. PR #457 remains merged and untouched. Its resulting base completed CI #1131 (run 37568924499), CodeQL #623 (run 37568924421, including successful actual `Analyze with CodeQL`), and JaCoCo ratchet #441 (run 37569227214, `ratchet=false`).

The unavailable local handoff commit `2d91c31fd9cbe481d320663d2632ec600f6c500a` never reached GitHub. The starting provenance commit established the existing draft without claiming SHA identity. Every implementation and repair belongs to this PR; no replacement branch/PR, merge, or auto-merge is authorized.

## CI provenance and execution-policy continuation

The execution-policy continuation starts at `aaf4e5ee74067540e866dbbac120c6f46089488d` and remains on draft PR #458.

| Run | Exact HEAD | Meaning |
| --- | --- | --- |
| CI #1133 / 37647267822 | `f84a5b76eab257bf3d866208b8abf0de8456e540` | Green documentation-only audit; the diff from protected master contained only this document. No production build-tool SBOM or HIGH/CRITICAL gate existed. |
| CI #1134 / 37652464678 | `aaf4e5ee74067540e866dbbac120c6f46089488d` | First implementation: verification, OpenAPI, Flyway, coverage, and build-tool SBOM passed; only `Enforce build-tool HIGH and CRITICAL advisory policy` failed, on the two Site-realm Jetty HIGH findings below. |

CI #1133 does not establish that the implementation passed. CI #1134's findings are deterministic policy evidence; rerunning unchanged source cannot change the ownership model. CodeQL #627 / 37652464750 completed actual analysis successfully on that implementation.

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

The real pinned scanner's `sbom --skip-db-update --ignorefile /dev/null --list-all-pkgs --exit-code 0 --format json` path reads back all 266 identities with exact versions, including classifier-qualified and aggregate POM PURLs. Repository policy then blocks every HIGH/CRITICAL match inside the validated authoritative execution set, retaining resolved-only HIGH/CRITICAL findings separately. Raw scanner status and full raw JSON remain independent evidence. No image/gosu exception applies.

Isolated committed fixtures prove:

- BeanUtils 1.9.4 yields `CVE-2025-48734`, severity HIGH, and is blocked by the repository policy.
- BeanUtils 1.11.0 yields no HIGH/CRITICAL finding and passes.
- Truncated CycloneDX returns nonzero scanner status.
- An empty database cache with updates disabled returns nonzero scanner status.

The vulnerable package exists only in fixture data. Focused offline regressions additionally reject new plugins/processors, missing roots/transitives, conflicting provenance, unresolved coordinates, malformed or truncated streams, partial/duplicate CycloneDX, omitted scanner packages, nonzero/error status, changed distribution bytes, and stale scan-ready outputs. Collector failures withhold the completion marker because Maven otherwise logs EventSpy exceptions and continues.

## Authoritative execution ownership

The complete reviewed graph is Boundary A: configured/effective roots, resolved descriptors and transitives, available filtered realms, actual processor/provider/booter/agent paths, and distribution libraries. Nothing is deleted from this graph to obtain policy success. Boundary B is the mechanically derived blocking execution set. The schema-2 contract no longer freezes goal-execution summaries as an allowlist; fresh ordered execution records live in ephemeral `execution-scope.json` and raw receipts.

Version-2 EventSpy receipts require matching goal starts and successes, one successful session, and an untruncated completion marker. Python additionally requires the independent `-X` Maven goal plan to match every successful receipt, with complete actual realms equal to their reviewed graphs. A dropped event/realm pair, failed collector, or edited executable subset cannot silently shrink scope. The scanner re-collects and validates these raw receipts before accepting the uploaded/generated inventory and scope.

The normal fourteen successful goal receipts involve Clean, Enforcer, JaCoCo, Spring Boot, Resources, Compiler, Surefire, Jar, and Failsafe. Docker's separate successful receipt is Dependency `go-offline`. Neither receipt stream nor its debug plan contains Site, Install, or Deploy. Repository search of all workflows, Dockerfile commands, and scripts finds no required `mvn site`, `site:*`, `install`, or `deploy` invocation: CodeQL uses `clean verify`; Docker/restore builds use `dependency:go-offline` and `package`; the protected-base OpenAPI comparison uses `test`; development uses `spring-boot:run`. GitHub Pages uses generated application OpenAPI output, not Maven Site. These other commands introduce no additional Site/Install/Deploy execution.

The collector deliberately calls `setupPluginRealm` for available roots at SessionStarted to obtain resolution evidence. Maven 3.10.0's [DefaultMavenPluginManager implementation](https://maven.apache.org/ref/3.10.0/maven-core/xref/org/apache/maven/plugin/internal/DefaultMavenPluginManager.html) resolves dependencies, constructs/imports class realms, and registers/discovers components; the collector never selects or invokes a goal through that call. Available-realm registration does not produce a `MojoStarted`/`MojoSucceeded` goal pair. Distribution ownership covers Maven infrastructure; goal ownership covers complete plugin realms once any goal executes. This does not assert absence of all class-loading/initializer activity or establish goal-specific vulnerability reachability.

The unchanged complete SBOM contains **266 PURLs**. Current fresh evidence partitions these into **211 blocking executable PURLs** and **55 resolved-only PURLs**, with no overlap or omission. Ten plugin roots execute and three are resolved-only. Executing plugin realms contain 129 unique artifacts (119 non-root artifacts); the union additionally includes 20 processor-path artifacts, 11 provider artifacts, 15 owned booter artifacts, two agents, 56 distribution JARs, and Maven's aggregate package identity. Counts overlap and must not be summed. All full-graph candidate/realm memberships and all 266 identities remain unchanged from the starting implementation.

Classification follows any observed successful goal, without plugin/package/CVE exclusions. Binding an available plugin to a lifecycle phase or invoking it directly automatically promotes its entire realm; a shared component blocks when any executed owner uses it. A new dependency graph still requires normal inventory review. The repository command observer discovers literal Maven invocations in workflows, the root Dockerfile, and shell scripts. It obtains goal prefixes from matching embedded plugin descriptors and automatically instruments newly required goals whose realm has not executed, including an added available-plugin goal. Collection independently requires coverage of every discovered goal by actual realm receipts. New commands cannot pass on the previous scope; dynamic goals or additional profiles fail closed pending separate evidence. Default lifecycle phases through verification and direct goals of already executed realms reuse full-realm ownership, without running another test lifecycle or a long-running development server. Declaration alone never proves execution.

Twenty-five general offline execution-boundary regressions and a scanner-preflight tamper regression pass, bringing the full Python suite to 255. They cover all executing categories, resolved-only advisory retention, lifecycle/direct promotion without graph edits, missing/conflicting starts/successes/realms/plans, dropped collector pairs, duplicate identities, shared ownership, tampered subsets, repository command discovery/coverage, automatic observation, descriptor identity, unsupported profiles/dynamic goals, and stale observation outputs. A separate real local command-discovery probe added `dependency:go-offline site:help` only to an isolated Dockerfile fixture. The observer executed the discovered goals and retained the same contract and all 266 PURLs, expanded executable ownership to 264 PURLs (two resolved-only), and correctly blocked both Jetty HIGH findings. This isolated investigation is not added to any required repository command; it proves discovery and actual full-realm promotion together.

## Narrow advisory remediation and resolved-only findings

The baseline's actually owned packages produced thirteen HIGH package/advisory matches, covering nine distinct CVEs. Build-plugin dependency overrides address only independently verified current advisories while retaining every plugin and platform version:

| Build-realm package | Remediation | Verified advisory |
| --- | --- | --- |
| Plexus Utils | 3.6.1 on the existing 3.x line; 4.0.3 on the existing 4.x line | CVE-2025-67030 / GHSA-6fmv-xxpf-w3cw |
| jsoup in Site | 1.23.2 | CVE-2026-75140 |
| Jackson Core in Boot plugin | 3.1.7 | CVE-2026-89407, CVE-2026-89425 |
| Jackson Databind in Boot plugin | 3.1.7 | CVE-2026-68497, CVE-2026-91776, CVE-2026-91777 |

BeanUtils 1.11.0 remains present in both Site and Dependency actual resolved realms; 1.9.4 is absent. Application dependency declarations/management, Java/Maven/Spring Boot, processor versions, application source, API, schema, migrations, Docker identities, CodeQL, compiler-warning policy, and JaCoCo baseline remain unchanged.

After remediation, the complete production scan retains:

| Maven component | HIGH advisory | Scanner-reported fixed version |
| --- | --- | --- |
| `org.eclipse.jetty:jetty-http:9.4.58.v20250814` | CVE-2026-2332 | 9.4.60 |
| `org.eclipse.jetty:jetty-security:9.4.58.v20250814` | CVE-2026-10050 | 9.4.63 |

Both belong exclusively to the currently resolved-only Site realm and appear unchanged in full Trivy evidence and `policy-result.json.resolved_only`. They do not block the current authoritative execution set. Any observed Site goal automatically makes its full realm and these findings blocking; no safe-Site assumption or vulnerability exception is involved.

Apache's current stable [Site release](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-site-plugin/maven-metadata.xml) remains 3.22.0; newer 4.x entries are milestones. [Jetty's official support table](https://jetty.org/download.html) marks 9.4 EOL/unsupported. Public Central metadata for [jetty-http](https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-http/maven-metadata.xml) and [jetty-security](https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-security/maven-metadata.xml) ends at 9.4.58.v20250814. Vendor advisories identify fixes [9.4.60 for CVE-2026-2332](https://github.com/jetty/jetty.project/security/advisories/GHSA-355h-qmc2-wpwf) and [9.4.63 for CVE-2026-10050](https://github.com/jetty/jetty.project/security/advisories/GHSA-2fvj-hgj9-j2gr). Direct Central requests for both artifacts' 9.4.60/9.4.63 POMs and JARs returned HTTP 404. No exact public 9.4 patch can be resolved from the repository's normal trusted repository; no unofficial fork, commercial/unpublished override, Jetty major override, or toolchain migration is introduced.

The full scan still reports vulnerabilities. Policy success means **no blocking HIGH/CRITICAL findings in the authoritative execution set**. Scanner/database errors and malformed or incomplete evidence remain failures; raw scanner status is checked, the threshold is unchanged, and there are no package-name/CVE-specific branches, ignore entries, or suppressed failures.

The execution-aware collector completed a fresh local Java 21 `clean verify`: 1,151 Surefire tests and 325 Failsafe PostgreSQL/Testcontainers tests, no failures/errors/skips. Compiler-warning policy passed. JaCoCo remained exactly 3,958 covered / 342 missed lines and 1,079 covered / 194 missed branches, satisfying the unchanged baseline. These local results do not substitute for hosted exact-final-head verification. PR #458 remains draft even if all hosted checks pass; no merge, auto-merge, or ready-for-review transition is authorized.

## Ownership and remaining gaps

[Container-security documentation](../operations/container-security.md#maven-build-tool-advisory-boundary) owns reproduction commands, scan policy, PR/scheduled trust boundaries, and reviewed contract updates. [Testing strategy](strategy.md#maven-build-tool-security-tests) owns regression and collector compilation boundaries. The existing `build` job reuses one verification lifecycle and uploads `build-tool-security-evidence` even on failure; weekly protected-master and manual selected-ref regeneration use the same vulnerability authority and cache model.

Dependency Review/Dependabot retain ordinary application/development graph ownership; their build-tool visibility is not assumed. Final-image Trivy/CycloneDX owns packaged application/PostgreSQL contents. Wrapper source and distribution checksum own bootstrap integrity. This Maven SBOM owns reviewed Maven-package advisories, including distribution libraries and dynamic providers/agents. Actions pins/Dependabot own action version provenance, and unchanged CodeQL plus behavioral tests own normal repository source.

Remaining limits are Wrapper shell logic, builder JDK/OS/native-tool advisories, arbitrary downloads, commands constructed outside literal Dockerfile/workflow/shell discovery, other project inputs/platforms/profiles/multi-module execution, class-initializer or goal-specific reachability, and database coverage/severity. The source asset compiles separately with Java 21 `-Xlint:all -Werror`; successful normal Java/Kotlin CodeQL analysis does not establish collector/Python analysis. Resolved-only findings require continued visibility and will block upon execution; availability-only evidence is not a claim of vulnerability safety. The next highest-value repository-owned coverage gap remains builder-stage JDK/OS/native-tool inventory and advisory evidence.
