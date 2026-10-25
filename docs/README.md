# Documentation index

This `docs/` folder contains practical documentation that is currently grounded in the codebase and repository workflows. API contracts should come from generated documentation, not manually duplicated endpoint inventories.

## Task-oriented navigation

Choose the relevant row and follow only the links needed for the task. These are starting points; verify current source, tests, and dependencies rather than treating the guide as an exhaustive inventory. Module responsibilities and boundaries remain in the existing module map and architecture documents.

Java package paths in the table are relative to the [production source root](../src/main/java/com/company/shop/) or [test source root](../src/test/java/com/company/shop/), as indicated by the column.

| Task | Guidance to consult as needed | Code or configuration | Tests and verification locations |
| --- | --- | --- | --- |
| Business-module behavior or boundaries | [Module map](./architecture/module-map.md), [overview](./architecture/overview.md), [ArchUnit rules](./architecture/archunit.md) | `module/`; choose the owning module from the module map. | Matching `module/` package; `architecture/` for dependency/layering rules. |
| Shared errors, validation, or configuration | [Overview](./architecture/overview.md), [error handling](./architecture/error-handling.md) | `common/`, `validation/`, `config/` | Matching `common/`, `validation/`, `config/` packages; affected module tests. |
| Authentication, authorization, JWT, or current-user handling | [Security architecture](./architecture/security-architecture.md) | `security/`, `config/`, and affected `module/` code | `security/`, `config/`, and affected module WebMvc/integration tests. |
| Database, persistence, or Flyway | [Database operations](./operations/database.md), [migration rules and compatibility](./operations/migrations.md) | [Migrations](../src/main/resources/db/migration/), owning `module/` entities/repositories, [compatibility decisions](../.github/database-migration-compatibility.json) | `persistence/` and affected module integration tests; [migration compatibility validator](../scripts/validate_migration_compatibility.py) and its tests in [scripts/tests/](../scripts/tests/). |
| CI, CodeQL, or JaCoCo | [Testing strategy](./testing/strategy.md#tooling), [coverage governance](./testing/strategy.md#coverage-reporting) | [Workflows](../.github/workflows/) (`ci.yml`, `codeql.yml`, `jacoco-ratchet.yml`), [pom.xml](../pom.xml), [coverage baseline](../.github/coverage/jacoco-baseline.json) | Policy validators in [scripts/](../scripts/) and their regressions in [scripts/tests/](../scripts/tests/); workflow jobs provide the hosted verification paths. |
| Docker or Maven/build-tool security | [Container security and reproduction](./operations/container-security.md), [Maven advisory boundary](./operations/container-security.md#maven-build-tool-advisory-boundary) | [Dockerfile](../Dockerfile), [PostgreSQL Dockerfile](../docker/postgres/Dockerfile), [wrapper configuration](../.mvn/), [mvnw](../mvnw), [pom.xml](../pom.xml), [security policies](../.github/security/), [scripts/](../scripts/) | Matching [scripts/tests/](../scripts/tests/) policy regressions; `persistence/support/` for Testcontainers/Ryuk execution. Follow the runbook for real scanner, image, and provenance evidence. |
| Operational scripts, startup, role ownership, or restore | [Local development](./operations/local-development.md), [production configuration](./operations/production-configuration.md), [database operations](./operations/database.md), [disaster recovery](./operations/disaster-recovery.md); [local administrator](./operations/local-administrator.md) and [credential recovery](./operations/local-administrator-credential-recovery.md) for operator tools | [scripts/](../scripts/), [Compose](../docker-compose.yml), [application resources](../src/main/resources/) | Matching [scripts/tests/](../scripts/tests/), `config/`, `persistence/`, and affected module tests; Docker/restore jobs in [CI](../.github/workflows/ci.yml). Read the owning runbook's safety and ownership boundaries before executing operational scripts. |

Use the [testing strategy](./testing/strategy.md) for focused Maven/Python commands and full verification prerequisites, including Java 21, Docker/PostgreSQL, and reviewed Ryuk preparation. Focused local checks do not replace required CI, CodeQL, coverage, or other applicable gates. For cross-module or security, API, persistence, transaction/concurrency, or operational impact, follow affected callers and consumers and expand to their tests and guidance.

## Policy
- [`documentation-policy.md`](./documentation-policy.md) — documentation source-of-truth rules, manual documentation scope, and future API documentation automation direction.

## Architecture
- [`architecture/overview.md`](./architecture/overview.md) — high-level runtime stack, module structure, and cross-cutting architecture.
- [`architecture/module-map.md`](./architecture/module-map.md) — module responsibilities, HTTP API ownership, and internal facades.
- [`architecture/outbox-and-notifications.md`](./architecture/outbox-and-notifications.md) — DB-backed order outbox and notification baseline.
- [`architecture/security-architecture.md`](./architecture/security-architecture.md) — JWT authentication, route authorization, CORS, CSRF, and actuator access.
- [`architecture/password-recovery-boundary.md`](./architecture/password-recovery-boundary.md) — fail-closed forgotten-password threat model, delivery alternatives, and required owner decisions.
- [`architecture/administrator-lifecycle-boundary.md`](./architecture/administrator-lifecycle-boundary.md) — fail-closed application administrator bootstrap, continuity, recovery, and required owner decisions.
- [`architecture/error-handling.md`](./architecture/error-handling.md) — API error contract, exception mapping, and business-exception metrics.
- [`architecture/archunit.md`](./architecture/archunit.md) — ArchUnit quality gate and currently enforced architecture rules.
- [`architecture/soft-delete-identifier-reservations.md`](./architecture/soft-delete-identifier-reservations.md) — logical retirement, physical retention, and business-identifier reservation decisions.

## API
- [`api/overview.md`](./api/overview.md) — current API documentation policy note and generated documentation entry points.

## Testing
- [`testing/strategy.md`](./testing/strategy.md) — current test categories, tooling, and Maven Wrapper commands.

## Operations
- [`operations/production-configuration.md`](./operations/production-configuration.md) — canonical, machine-checked production environment contract, ownership, defaults, and deployment handoff.
- [`operations/local-development.md`](./operations/local-development.md) — local PostgreSQL setup, profiles, startup, and smoke checks.
- [`operations/production-edge.md`](./operations/production-edge.md) — production edge trust boundary and public-authentication abuse controls.
- [`operations/http-capacity.md`](./operations/http-capacity.md) — production Tomcat admission, queueing, downstream-capacity, overload, and metric ownership.
- [`operations/http-request-bounds.md`](./operations/http-request-bounds.md) — inbound body/header parsing, slow-client, rejection, and edge-ownership audit.
- [`operations/jwt-key-rotation.md`](./operations/jwt-key-rotation.md) — bounded JWT signing-key rollover, retirement, rollback, and emergency-compromise procedure.
- [`operations/database.md`](./operations/database.md) — PostgreSQL schema ownership and database conventions.
- [`operations/data-retention.md`](./operations/data-retention.md) — operational-record retention classes, correctness boundaries, and prerequisites for future purge or archival.
- [`operations/disaster-recovery.md`](./operations/disaster-recovery.md) — production backup/PITR ownership, logical restore baseline, Flyway and role restoration, and rehearsal evidence.
- [`operations/container-security.md`](./operations/container-security.md) — Dockerfile linting, image vulnerability scanning, SBOM artifacts, and local reproduction commands.
- [`operations/migrations.md`](./operations/migrations.md) — Flyway migration sequence and migration rules.
- [`operations/observability.md`](./operations/observability.md) — request correlation, logs, actuator exposure, Prometheus, and custom metrics.
- [`operations/application-lifecycle.md`](./operations/application-lifecycle.md) — readiness, liveness, graceful termination, worker recovery, and deployment-owned draining.
- [`operations/outbox-observability.md`](./operations/outbox-observability.md) — admin transactional outbox health indicators, filters, requeue auditability, and action log queries.
- [`operations/notification-observability.md`](./operations/notification-observability.md) — admin notification delivery indicators, filters, requeue auditability, and action log queries.
- [`operations/release-checklist.md`](./operations/release-checklist.md) — lightweight pre-merge release checklist.

## Intentionally skipped for now

The following candidate docs are not currently maintained because they would duplicate existing files or add process that is not represented in the repository:

- `architecture/decisions/*.md` — no ADR history is maintained in the repo.
- Per-resource API files such as `api/carts.md` or `api/orders.md` — these would duplicate generated API contracts; `api/overview.md` remains a policy and entry-point page for generated documentation rather than a manually maintained endpoint inventory.
- Detailed testing governance or release-runbook documents — current practices are covered by the testing strategy, CI workflow, and lightweight release checklist.
- `domain/*.md` — domain behavior is best represented by entities, services, migrations, and focused tests until a separate domain reference becomes useful.
