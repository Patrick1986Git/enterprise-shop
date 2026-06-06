# Documentation index

This `docs/` folder contains practical documentation that is currently grounded in the codebase and repository workflows.

## Architecture
- [`architecture/overview.md`](./architecture/overview.md) — high-level runtime stack, module structure, and cross-cutting architecture.
- [`architecture/module-map.md`](./architecture/module-map.md) — module responsibilities, HTTP API ownership, and internal facades.
- [`architecture/outbox-and-notifications.md`](./architecture/outbox-and-notifications.md) — DB-backed order outbox and notification baseline.
- [`architecture/security-architecture.md`](./architecture/security-architecture.md) — JWT authentication, route authorization, CORS, CSRF, and actuator access.
- [`architecture/error-handling.md`](./architecture/error-handling.md) — API error contract, exception mapping, and business-exception metrics.
- [`architecture/archunit.md`](./architecture/archunit.md) — ArchUnit quality gate and currently enforced architecture rules.

## API
- [`api/overview.md`](./api/overview.md) — endpoint inventory grouped by area, including access expectations.

## Testing
- [`testing/strategy.md`](./testing/strategy.md) — current test categories, tooling, and Maven Wrapper commands.

## Operations
- [`operations/local-development.md`](./operations/local-development.md) — local PostgreSQL setup, profiles, startup, and smoke checks.
- [`operations/database.md`](./operations/database.md) — PostgreSQL schema ownership and database conventions.
- [`operations/migrations.md`](./operations/migrations.md) — Flyway migration sequence and migration rules.
- [`operations/observability.md`](./operations/observability.md) — request correlation, logs, actuator exposure, Prometheus, and custom metrics.
- [`operations/release-checklist.md`](./operations/release-checklist.md) — lightweight pre-merge release checklist.

## Intentionally skipped for now

The following candidate docs are not currently maintained because they would duplicate existing files or add process that is not represented in the repository:

- `architecture/decisions/*.md` — no ADR history is maintained in the repo.
- Per-resource API files such as `api/carts.md` or `api/orders.md` — the endpoint set is still maintainable in one API overview.
- Detailed testing governance or release-runbook documents — current practices are covered by the testing strategy, CI workflow, and lightweight release checklist.
- `domain/*.md` — domain behavior is best represented by entities, services, migrations, and focused tests until a separate domain reference becomes useful.
