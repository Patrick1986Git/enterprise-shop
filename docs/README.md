# Documentation index

This `docs/` folder contains only documentation that is currently justified by the codebase and workflows in this repository.

## Created now

### Architecture
- [`architecture/overview.md`](./architecture/overview.md) — high-level component and layering map.
- [`architecture/module-map.md`](./architecture/module-map.md) — module responsibilities and package ownership.
- [`architecture/security-architecture.md`](./architecture/security-architecture.md) — JWT and authorization model.
- [`architecture/error-handling.md`](./architecture/error-handling.md) — API error contract and exception mapping strategy.
- [`architecture/archunit.md`](./architecture/archunit.md) — ArchUnit quality gate and currently enforced architecture rules.

### API
- [`api/overview.md`](./api/overview.md) — endpoint inventory grouped by area, with auth expectations.

### Testing
- [`testing/strategy.md`](./testing/strategy.md) — current testing profile (unit/WebMvc/`@DataJpaTest` + PostgreSQL Testcontainers support), coverage focus, and commands.

### Operations
- [`operations/local-development.md`](./operations/local-development.md) — local setup and app startup flow.
- [`operations/database.md`](./operations/database.md) — PostgreSQL setup model and schema ownership.
- [`operations/migrations.md`](./operations/migrations.md) — Flyway migration baseline and rules for future changes.
- [`operations/observability.md`](./operations/observability.md) — request-id based HTTP/log correlation and debugging flow.

## Intentionally skipped for now

The following candidate docs from the target structure were **not** created because they are not yet justified by current repository maturity or would duplicate existing information:

- `architecture/decisions/*.md` (ADRs): no recorded decision history exists in repo commits/docs yet.
- `api/authentication.md`, `api/errors.md`, and per-resource API files (`carts.md`, `orders.md`, etc.): endpoint set is still compact enough for one maintainable inventory file.
- `testing/test-pyramid.md`, detailed guideline docs, and `testing/plans/*`: no formal test governance process or active plan artifacts are present.
- `operations/docker.md`, `operations/troubleshooting.md`, `operations/release-checklist.md`: current operational surface is small; splitting would add maintenance overhead without new clarity.
- `domain/*.md`: domain behavior is still best represented directly in entities/services/tests; separate domain docs would mostly restate code.
- `contributors/*.md`: no repository-level contribution convention beyond existing `AGENTS.md`; creating a second policy source risks drift.

If any of the above areas become active collaboration pain points, add those documents incrementally.
