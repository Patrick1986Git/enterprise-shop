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
