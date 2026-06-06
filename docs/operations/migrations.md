# Flyway migrations

Migrations live under `src/main/resources/db/migration` and are applied in version order. Historical migrations are immutable for normal development work.

## Current migration sequence

| Version | File | Purpose |
| --- | --- | --- |
| V1 | `V1__schema.sql` | Initial schema baseline for users/roles, catalog, orders, payments, and related core tables. |
| V2 | `V2__reviews.sql` | Adds product reviews. |
| V3 | `V3__add_product_rating_stats.sql` | Adds product rating statistics support. |
| V4 | `V4__promotions.sql` | Adds promotion/discount-code support. |
| V5 | `V5__full_text_search.sql` | Adds full-text search support/artifacts. |
| V6 | `V6__cart.sql` | Adds carts and cart items. |
| V7 | `V7__create_product_images.sql` | Adds product images. |
| V8 | `V8__payment_intent_tracking.sql` | Adds Stripe payment-intent tracking fields. |
| V9 | `V9__payments_order_uniqueness.sql` | Enforces payment/order uniqueness. |
| V10 | `V10__product_optimistic_locking.sql` | Adds product optimistic locking support. |
| V11 | `V11__rename_product_unique_constraints.sql` | Normalizes product unique constraint names. |
| V12 | `V12__users_email_case_insensitive_unique.sql` | Adds case-insensitive unique user email index. |
| V13 | `V13__add_missing_check_constraints.sql` | Backfills invalid data and adds non-negative/validity check constraints. |
| V14 | `V14__add_status_check_constraints.sql` | Normalizes and constrains order/payment statuses and payment methods. |
| V15 | `V15__order_items_drop_unused_audit_soft_delete_columns.sql` | Removes unused audit/soft-delete columns from order items. |
| V16 | `V16__create_stripe_webhook_events.sql` | Adds Stripe webhook event idempotency table. |
| V17 | `V17__order_item_product_snapshot.sql` | Adds required product name/SKU snapshots to order items. |
| V18 | `V18__order_user_snapshot.sql` | Adds required user email snapshot to orders. |
| V19 | `V19__create_outbox_events.sql` | Adds `outbox_events` with JSON payload, status, attempts, processing timestamps, failure details, and indexes. |
| V20 | `V20__create_notifications.sql` | Adds `notifications` with type/recipient/content/status/source event, sent/failure state, and indexes. |

## Rules for future migrations

- Add a new `V{next}__descriptive_name.sql` file for every schema change.
- Do not edit older migrations as part of ordinary feature work.
- Include data backfills before adding `NOT NULL`, uniqueness, or check constraints when existing rows may violate the new rule.
- Keep entity mappings, repository assumptions, and migration SQL in sync.
- Add or update narrow persistence/migration tests when introducing constraints, indexes, required columns, or schema objects.

## Timestamp note

The migration history contains both plain `TIMESTAMP` and `TIMESTAMP WITH TIME ZONE` columns. V19 and V20 use `TIMESTAMP WITH TIME ZONE` for outbox/notification lifecycle fields, while earlier migrations include plain `TIMESTAMP` fields such as Stripe webhook processing timestamps. This is the current schema history and should not be rewritten retroactively.
