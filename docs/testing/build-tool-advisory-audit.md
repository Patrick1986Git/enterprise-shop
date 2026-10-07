# Build-tool advisory coverage audit

Protected-master baseline: `bcba8c7f7b9bcae2fda58a6025ce4eb0f3b6eb00`.

This audit continues the supply-chain work after PR #457 without reopening or modifying that merged remediation.

PR #457 removed the confirmed vulnerable BeanUtils version from the affected Maven Dependency Plugin and Maven Site Plugin realms.

Post-merge evidence for that protected-master baseline is green:

- CI #1131: success.
- CodeQL #623: success, including actual `Analyze with CodeQL`.
- JaCoCo ratchet #441: success with `ratchet=false`.

The remaining objective is to establish whether the repository can provide deterministic advisory coverage for build-time components that execute during Maven builds but are not guaranteed to be represented by the ordinary application dependency graph or final runtime image.

The audit must treat plugin realms, annotation-processor paths, dynamic test providers, Maven distribution/bootstrap libraries, and final-image contents as distinct ownership domains.

The first implementation candidate to evaluate is repository-owned build-tool inventory -> dedicated CycloneDX evidence -> the existing pinned Trivy authority.

No claim of complete coverage is valid until inventory completeness, malformed/incomplete-evidence failure behavior, MapStruct/Hibernate processor presence, plugin transitive capture, and scanner handling are proven.

Do not add a second vulnerability database or privileged dependency-submission workflow unless the existing Trivy/native mechanisms are proven insufficient.

Do not change application behavior, public APIs, persistence, Flyway migrations, Java/Maven/Spring Boot versions, compiler-warning policy, or the JaCoCo baseline as part of the audit.

Keep every follow-up, repair, hosted-CI fix, CodeQL fix, and review-thread resolution on the single current pull request created from `codex/build-tool-advisory-inventory`.

The original local Codex handoff commit `2d91c31fd9cbe481d320663d2632ec600f6c500a` was not present in the GitHub object database after the failed push. This repository commit re-establishes durable audit provenance without claiming SHA identity with that unavailable local commit.

## Resolution and execution findings

The initial PR #458 HEAD `2175900fecd95e665e2b015cb4f032e61b46ba96`
was investigated with the checked-in Maven Wrapper, Maven 3.10.0, and a complete
Temurin Java 21.0.12.1 JDK. Local `clean verify` completed with 1,151 Surefire
tests and 325 Failsafe/PostgreSQL/Testcontainers tests, without failures or skips.
These are local baseline results, not verification of a later PR HEAD.

The effective model has twelve plugin roots. Nine execute in `clean verify`:
Spring Boot 4.1.1, Compiler 3.16.0, Surefire 3.5.6, Failsafe 3.6.0,
JaCoCo 0.8.15, Enforcer 3.6.3, Clean 3.5.0, Resources 3.5.0, and Jar 3.5.1.
Install 3.1.4, Deploy 3.1.4, and Site 3.22.0 are effective roots outside that
lifecycle. Docker also directly executes Dependency Plugin 3.10.0.

Maven's `dependency:resolve-plugins` report contains 181 unique artifacts across
the twelve effective roots. A Maven EventSpy probe using `MavenPluginManager`
independently resolved the configured descriptors and their filtered class
realms. Descriptor candidates and actual realm URLs are different evidence:
Maven excludes some dependencies and imports its own API libraries instead.
For example, Dependency Plugin's descriptor has 49 artifacts but its realm has
43 URLs. A flat union of resolution candidates therefore cannot by itself
prove the executing versions or establish a production advisory gate.

Both compiler executions use MapStruct processor 1.6.3 and Hibernate processor
7.4.11.Final on an explicit processor path. Resolver events provide authoritative
coordinates for those paths; filenames alone are not provenance. Surefire and
Failsafe actually select `JUnitPlatformProvider`, with provider artifacts
3.5.6 and 3.6.0 respectively and JUnit Platform Launcher 6.0.3. Forked JVMs also
execute JaCoCo's `runtime` classifier agent and Mockito Core 5.23.0.

All 56 Maven distribution/bootstrap JARs byte-match artifacts resolved from
the published Apache Maven 3.10.0 distribution POM. Five lack embedded
`pom.properties`: AOP Alliance 1.0, ASM 9.10.1, Guice 5.1.0 with classifier
`classes`, Javax Inject 1, and JSpecify 1.0.1. Their identities came from the
distribution's resolved dependency metadata and byte equality, not names.
The Wrapper shell script remains under the existing reviewed source/checksum
integrity boundary; Maven package scanning is not shell or operating-system
advisory coverage.

## Pinned Trivy experiment

The tested image is the existing CI authority:
`ghcr.io/aquasecurity/trivy:0.72.0@sha256:cffe3f5161a47a6823fbd23d985795b3ed72a4c806da4c4df16266c02accdd6f`.
Its vulnerability database reported `UpdatedAt=2026-10-07T07:38:55.515026687Z`.

An isolated CycloneDX 1.5 Maven-PURL fixture for BeanUtils 1.9.4 produced
`CVE-2025-48734`, severity HIGH, and exit status 1 with
`sbom --skip-db-update --severity HIGH,CRITICAL --exit-code 1 --format json`.
The otherwise identical 1.11.0 fixture returned status 0 with no HIGH/CRITICAL
finding. Truncated JSON failed with status 1. No vulnerable production
dependency was introduced. `--list-all-pkgs` also preserved all 283 package
identities in a diagnostic candidate union, including Guice's classifier PURL.
That union is experimental evidence, not a completeness-approved production
SBOM; generated evidence remains uncommitted.

Database acquisition from the default GCR mirror was denied by this local
environment's network policy and exited nonzero. Trivy's standard GHCR database
repository succeeded with normal TLS verification. The failed download was
not treated as a clean scan, and no alternate vulnerability database was added.

The diagnostic union returned thirteen HIGH package/advisory matches covering
nine distinct CVEs. They include [Plexus Utils directory traversal](https://github.com/advisories/GHSA-6fmv-xxpf-w3cw)
in several actually loaded plugin realms, Site Plugin's Jetty and jsoup
dependencies, and Spring Boot Maven Plugin's Jackson 3.1.5 dependencies.
BeanUtils 1.11.0 remains present in Dependency and Site resolution evidence;
BeanUtils 1.9.4 is absent from the inventoried candidates.

The [Jetty Digest authentication advisory](https://github.com/jetty/jetty.project/security/advisories/GHSA-2fvj-hgj9-j2gr)
lists a patched 9.4.63 line, but public Maven Central metadata for
`org.eclipse.jetty:jetty-security` ends at 9.4.58.v20250814. This audit does not
silently replace Jetty with another major line or apply a runtime-image
exception to build tools. A version/advisory match does not establish
exploitability through a particular Maven goal.
