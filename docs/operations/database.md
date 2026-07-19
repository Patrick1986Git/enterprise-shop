# Database operations

## Local Docker PostgreSQL

| Setting | Default |
| --- | --- |
| Container service | `postgres` |
| PostgreSQL version | `16` |
| Database | `enterprise_shop_dev` |
| Host binding | `127.0.0.1:${POSTGRES_HOST_PORT:-5433}:5432` |
| Admin user | `${POSTGRES_USER:-postgres}` |
| Runtime application user | `${APP_DB_USER:-shop_dev}` |
| Volume | `enterprise_shop_postgres_volume` |

The host may separately run a system PostgreSQL instance on `localhost:5432`; Docker PostgreSQL intentionally uses port `5433` by default. The custom PostgreSQL image preserves the Polish full-text-search dictionary files required by Flyway migration V5.

The `dev` profile keeps Hibernate in `ddl-auto: validate`. Schema changes must come from Flyway, not Hibernate auto-DDL. Local Flyway uses the admin/bootstrap identity by default through `spring.flyway.url`, `spring.flyway.user`, and `spring.flyway.password`; the application datasource uses the least-privilege runtime identity.

## Least-privilege runtime grants

The runtime role has `LOGIN`, `CONNECT` on `enterprise_shop_dev`, `USAGE` on schema `public`, DML privileges on existing application tables, sequence privileges needed by generated IDs, and function execution where required. It is explicitly kept as `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION` and does not own the PostgreSQL server.

Run the idempotent bootstrap whenever local role grants need refreshing:

```bash
docker compose up -d --wait postgres
docker compose up --wait --force-recreate database-role-bootstrap
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
docker volume inspect enterprise_shop_postgres_volume
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
