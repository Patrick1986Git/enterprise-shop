# Database operations

## Local Docker PostgreSQL

| Setting | Default |
| --- | --- |
| Container service | `postgres` |
| PostgreSQL version | `18` |
| Database | `enterprise_shop_dev` |
| Host binding | `127.0.0.1:${POSTGRES_HOST_PORT:-5433}:5432` |
| Admin user | `${POSTGRES_USER:-postgres}` |
| Runtime application user | `${APP_DB_USER:-shop_dev}` |
| Volume | `enterprise_shop_postgres18_volume` |

The host may separately run a system PostgreSQL instance on `localhost:5432`; Docker PostgreSQL intentionally uses port `5433` by default. The custom PostgreSQL image preserves the Polish full-text-search dictionary files required by Flyway migration V5.

The `dev` profile keeps Hibernate in `ddl-auto: validate`. Schema changes must come from Flyway, not Hibernate auto-DDL. Local Flyway uses the admin/bootstrap identity by default through `spring.flyway.url`, `spring.flyway.user`, and `spring.flyway.password`; the application datasource uses the least-privilege runtime identity.

## Production migration identity

Production requires two distinct PostgreSQL login identities. `DATABASE_USERNAME` and `DATABASE_PASSWORD` configure the least-privilege runtime datasource. `FLYWAY_USER` and `FLYWAY_PASSWORD` configure the schema migration identity; `FLYWAY_URL` may select a dedicated migration endpoint and otherwise uses `DATABASE_URL`. None of the production Flyway credentials default to the runtime credentials. Missing migration credentials therefore stop startup rather than silently running DDL through the application identity.

The migration identity applies Flyway before Hibernate validates the schema and owns the objects it creates. The runtime identity receives only `CONNECT`, schema `USAGE`, application DML, required sequence access, and required function execution. It must not be a superuser, database or schema owner, table owner, or hold `CREATEDB`/`CREATEROLE`. Production provisioning must establish default privileges for objects created by `FLYWAY_USER`, grant the runtime privileges, and then start the application with both credential sets available through secret management.

This ownership split is part of the V45 append-only boundary. The mutation-rejection trigger deliberately permits the table owner to perform maintenance, while rejecting `UPDATE` and `DELETE` from the non-owner runtime session with SQLSTATE `42501`. Running Flyway as `DATABASE_USERNAME` would make a fresh deployment's runtime identity the protected tables' owner and defeat that boundary.

Production uses `baseline-on-migrate: false`. Supported fresh deployments migrate an empty database from V1, and upgrades retain their existing `flyway_schema_history`; neither path needs automatic baselining. A non-empty schema without Flyway history is rejected so an accidental connection cannot silently adopt an unmanaged schema. Any exceptional legacy adoption requires a reviewed, explicit one-time Flyway baseline procedure before normal startup. Development retains its existing automatic-baseline compatibility.

### Existing production database rollout

Databases first migrated before dedicated Flyway credentials were required may still be owned by the runtime identity. Merely configuring a new `FLYWAY_USER` does not change existing PostgreSQL ownership. Production startup therefore checks, after Flyway and Hibernate initialization, that the runtime connection does not own any of the three V45-protected ADMIN action-log tables and fails if legacy ownership remains.

Use `scripts/transfer-prod-db-ownership.sh` only as a reviewed maintenance operation for that legacy state. It requires a separate PostgreSQL superuser administrative connection through `DATABASE_ADMIN_URL`/`DATABASE_ADMIN_USER`, the exact expected `DATABASE_NAME`, `LEGACY_RUNTIME_USER`, `FLYWAY_USER`, and optional `APP_SCHEMA`. The script validates identifiers and the connected database, refuses equal roles, and refuses `REASSIGN OWNED` when the legacy role owns another database, a tablespace, or objects outside the selected application schema. Within those safeguards, reassignment covers the current database's schema-managed tables, indexes, sequences, functions, text-search objects, extensions, schema, and database ownership; the script then restores current and default runtime DML, sequence, and function grants. It never reads or prints a database password; supply the administrative password through the normal `PGPASSWORD`/password-file mechanism.

For an existing database, use this ordering:

1. Back up the database and provision the distinct migration identity.
2. Audit the legacy runtime role's ownership and review the transfer script inputs against the intended database and schema.
3. Stop application writers, run the privileged ownership transfer, and independently verify owners and runtime grants.
4. Configure `DATABASE_*` for runtime and `FLYWAY_*` for migration through secret management.
5. Start the application. Flyway validates the existing history and applies only pending migrations, Hibernate validates the schema, and the production ownership invariant rejects any remaining protected-table ownership.
6. Verify application health, normal runtime DML, and V45 INSERT/SELECT versus UPDATE/DELETE behavior before restoring traffic.

Do not grant the runtime role membership in the migration or administrative role. Do not use automatic baselining or edit `flyway_schema_history` as an ownership-transfer mechanism.

## Least-privilege runtime grants

The runtime role has `LOGIN`, `CONNECT` on `enterprise_shop_dev`, `USAGE` on schema `public`, DML privileges on existing application tables, sequence privileges needed by generated IDs, and function execution where required. It is explicitly kept as `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION` and does not own the PostgreSQL server.

The three operator-history tables (`notification_admin_action_logs`, `outbox_event_admin_action_logs`, and `reservation_expiration_admin_action_logs`) are append-only for non-owner runtime identities. The runtime role can insert and query their rows, but database triggers reject row updates and deletes even though the general table grants include those operations. Flyway creates the triggers while connected as the administrative schema owner, after the one-shot role bootstrap and before the runtime datasource begins normal work. Re-running the broad, idempotent role bootstrap therefore cannot remove the protection.

This boundary protects against accidental application or repository mutation and direct mutation through runtime credentials. It does not protect against a PostgreSQL superuser, the table owner, or a migration administrator deliberately changing or disabling the triggers. Administrative retention, if introduced in the future, must use a separate privileged identity; no audit-log retention process currently exists. A future operator-history table must attach `reject_runtime_admin_action_log_mutation()` in the same forward Flyway migration that creates the table.

Run the idempotent bootstrap whenever local role grants need refreshing. PostgreSQL is long-running and uses `up --wait`; the bootstrap is a one-shot administrative task and uses `run`, where exit code 0 is success and `--rm` removes the temporary one-off container:

```bash
docker compose up -d --wait postgres
docker compose run --rm --no-deps --build database-role-bootstrap
```

For the full stack:

```bash
docker compose --profile full up -d --build --wait
```

## Inspection commands

```bash
docker compose exec -T postgres pg_isready -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-enterprise_shop_dev}"
docker compose exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-enterprise_shop_dev}" \
  -c "SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolreplication FROM pg_roles WHERE rolname = 'shop_dev';"
docker volume inspect enterprise_shop_postgres18_volume
```

Do not run `docker compose down -v` unless you intentionally want to delete local database data.

## Test database independence

Persistence integration tests use independent Testcontainers PostgreSQL containers and do not use the Docker Compose database or its named volume. Testcontainers may use its dynamically created database owner for isolated Flyway migrations.

## Schema ownership

| Area | Tables/features |
| --- | --- |
| User/security | Users, roles, user-role join, case-insensitive email uniqueness. |
| Catalog | Categories, products, product images, product search/rating support. |
| Reviews | Product reviews and related constraints. |
| Cart | Carts and cart items. |
| Orders/payments | Orders, order items, discount codes, payments, Stripe webhook idempotency events. |
| Outbox/notifications | `outbox_events` and `notifications`. |

## Persistence conventions

- New schema changes require a new Flyway migration.
- Do not edit historical migrations unless explicitly directed for a controlled repair.
- Keep JPA mappings and migrations aligned; `ddl-auto: validate` should continue to pass.
- Preserve database constraints for uniqueness, status values, non-negative amounts/stock, and relationship integrity.
- Payment/order/cart/stock changes are consistency-sensitive and require focused tests.

## Timestamp note

Historical migrations use plain `TIMESTAMP` columns in several tables. Newer outbox and notification migrations use `TIMESTAMP WITH TIME ZONE` for `created_at`, processing/sent timestamps, and related event state. This documentation records the current real state; do not rewrite existing migrations in documentation-only work.
