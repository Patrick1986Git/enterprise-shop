# Database

## Runtime database

The application uses PostgreSQL with schema evolution managed by Flyway migrations in `src/main/resources/db/migration`.

Local development uses Docker Compose PostgreSQL:

| Setting | Value |
| --- | --- |
| Container service | `postgres` |
| Host bind address | `127.0.0.1` |
| Host port | `5433` |
| Container port | `5432` |
| Database | `enterprise_shop_dev` |
| Username | `postgres` |
| Password | `postgres` |
| Volume | `enterprise_shop_postgres_volume` |

The host may separately run a system PostgreSQL instance on `localhost:5432`. Docker PostgreSQL intentionally uses `127.0.0.1:${POSTGRES_HOST_PORT:-5433}:5432` so the two can coexist.

The `dev` profile points host-run Spring Boot to `jdbc:postgresql://localhost:5433/enterprise_shop_dev` by default and uses Hibernate `ddl-auto: validate`; schema changes must come from Flyway, not Hibernate auto-DDL. In the full Compose profile, the app container uses `jdbc:postgresql://postgres:5432/enterprise_shop_dev` for container-to-container networking.

## Compose operations

```bash
docker compose up -d --wait postgres
docker compose exec -T postgres pg_isready -U postgres -d enterprise_shop_dev
docker compose ps
docker compose logs postgres
docker compose --profile full down
```

Use `docker volume inspect enterprise_shop_postgres_volume` to inspect the local database volume. Do not run `docker compose down -v` unless you intentionally want to delete local database data.

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
- Persistence integration tests use independent Testcontainers PostgreSQL containers and do not use the Docker Compose database or its named volume.

## Timestamp note

Historical migrations use plain `TIMESTAMP` columns in several tables. Newer outbox and notification migrations use `TIMESTAMP WITH TIME ZONE` for `created_at`, processing/sent timestamps, and related event state. This documentation records the current real state; do not rewrite existing migrations in documentation-only work.
