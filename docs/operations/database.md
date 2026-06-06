# Database

## Runtime database

The application uses PostgreSQL with schema evolution managed by Flyway migrations in `src/main/resources/db/migration`.

Local development uses Docker Compose PostgreSQL:

| Setting | Value |
| --- | --- |
| Container service | `postgres` |
| Host port | `5433` |
| Container port | `5432` |
| Database | `enterprise_shop_dev` |
| Username | `postgres` |
| Password | `postgres` |

The `dev` profile points Spring to `jdbc:postgresql://localhost:5433/enterprise_shop_dev` and uses Hibernate `ddl-auto: validate`; schema changes must come from Flyway, not Hibernate auto-DDL.

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
