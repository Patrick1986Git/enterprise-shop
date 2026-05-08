# Database notes

## Engine and local topology
- PostgreSQL is the primary database.
- Local dev DB is run via `docker-compose.yml` using a custom image from `docker/postgres`.
- The custom image copies Polish Hunspell dictionary files used by full-text-search migration logic.

## Connection model
### Development profile (`application-dev.yml`)
- URL: `jdbc:postgresql://localhost:5433/enterprise_shop_dev`
- User/password: `postgres` / `postgres`
- JPA `ddl-auto=validate` (schema must match migrations)
- Flyway enabled and pointed to `classpath:db/migration`

### Production profile (`application-prod.yml`)
- Uses `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
- Also keeps `ddl-auto=validate`

## Local bootstrap script
- Local bootstrap SQL is kept in `scripts/db-setup.sql`.
- The script is intended for fresh local instances and must be run as PostgreSQL superuser.
- The script is not idempotent (rerun may fail if role/database already exists).
- Execution steps are documented in [local-development.md](./local-development.md#2-prepare-database-userpermissions).

## Schema ownership model
- Baseline schema is in `V1__schema.sql`.
- Later migrations extend/adjust schema for reviews, ratings, promotions, full text search, cart, product images, payment tracking, uniqueness constraints, optimistic locking, and email uniqueness handling.
- Recent hardening migrations also added/cleaned DB invariants:
  - `V13` adds missing numeric/date check constraints.
  - `V14` adds allowed-value constraints for order/payment statuses and payment method.
  - `V15` removes unused audit/soft-delete columns from `order_items`.

## Full-text search specifics
Migration `V5__full_text_search.sql` introduces:
- `unaccent` extension
- Polish text search dictionary + configuration
- generated `products.search_vector`
- GIN index for search performance

This is why the repository ships dictionary files under `docker/postgres/tsearch_data`.

## Timestamp and audit model

### Current baseline
- Core domain tables created in `V1` use `TIMESTAMP` (without time zone) for `created_at`, `updated_at`, `deleted_at`.
- JPA audit base classes (`AuditableEntity`, `SoftDeleteEntity`) use `LocalDateTime`, which currently matches the DB column family (`TIMESTAMP`).
- Soft delete is already part of the model for selected aggregates (`deleted`, `deleted_at`) and is enforced in several places via `@SQLRestriction("deleted = false")` and repository methods.

### Known inconsistencies to address in a dedicated schema-hardening PR
- `product_images.created_at` is declared explicitly as `TIMESTAMP WITHOUT TIME ZONE`, while other tables use plain `TIMESTAMP` (semantically equivalent in PostgreSQL, but stylistically inconsistent).
- Soft-delete filtering is not yet fully uniform across all aggregates and may evolve in dedicated soft-delete hardening work.
- Timestamp defaults are not fully uniform (`CURRENT_TIMESTAMP` vs `NOW()`, and several `updated_at` columns have no DB default).

### Recommendation stance (current)
- Keep existing behavior unchanged for now; avoid editing historical migrations.
- Prefer introducing `TIMESTAMPTZ` only through additive migration strategy (new columns + backfill + application switch + cleanup) rather than in-place type rewrites.
- Keep soft delete for business entities where recovery/auditability matters (already true for most aggregates here); evaluate exemptions table-by-table (e.g., strictly dependent child rows).

### Narrow hardening decision (current PR scope)
- Standardize documentation for future migrations to one SQL style: `TIMESTAMP` + `CURRENT_TIMESTAMP` for `created_at` defaults.
- Treat `NOW()` and explicit `TIMESTAMP WITHOUT TIME ZONE` as legacy syntax in this project unless a migration requires it for a specific technical reason.
- Postpone schema-wide type migration to `TIMESTAMPTZ` to a dedicated rollout PR with data migration plan.
