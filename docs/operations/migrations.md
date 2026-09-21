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
| V21 | `V21__add_notification_delivery_attempts.sql` | Adds notification delivery attempt tracking. |
| V22 | `V22__add_notification_next_attempt_at.sql` | Adds notification next-attempt scheduling support and an index for pending due notifications. |
| V23 | `V23__make_notification_source_event_unique.sql` | Enforces uniqueness for non-null notification source event ids. |
| V24 | `V24__add_notification_last_attempt_at.sql` | Adds notification last-attempt timestamp tracking. |
| V25 | `V25__add_notification_requeue_metadata.sql` | Adds notification requeue count and timestamp metadata. |
| V26 | `V26__add_notification_last_requeued_by.sql` | Adds notification last-requeued-by actor metadata. |
| V27 | `V27__create_notification_admin_action_logs.sql` | Adds notification admin action audit logs and lookup indexes. |
| V28 | `V28__add_outbox_event_requeue_metadata.sql` | Adds outbox event requeue count, timestamp, and actor metadata. |
| V29 | `V29__create_outbox_event_admin_action_logs.sql` | Adds outbox event admin action audit logs and lookup indexes. |
| V30 | `V30__add_outbox_event_last_attempt_at.sql` | Adds outbox event last-attempt timestamp tracking. |
| V31 | `V31__add_outbox_observability_indexes.sql` | Adds outbox event indexes for status with last-attempt timestamp and attempts filters. |
| V32 | `V32__add_outbox_event_processed_at_index.sql` | Adds an outbox event index for status with processed timestamp filters. |
| V33 | `V33__add_notification_status_attempts_index.sql` | Adds a notification index for status with attempts filters. |
| V34 | `V34__add_notification_status_last_attempt_at_index.sql` | Adds a notification index for status with last-attempt timestamp filters. |
| V35 | `V35__add_notification_status_sent_at_index.sql` | Adds a notification index for status with sent timestamp filters. |
| V36 | `V36__add_notification_requeue_count_last_requeued_at_index.sql` | Adds a notification index for requeue count with last-requeued timestamp filters. |
| V37 | `V37__add_outbox_retry_dead_letter_foundation.sql` | Adds nullable outbox retry/dead-letter foundation columns, allows the `DEAD_LETTER` status, and adds a status/next-attempt index. |
| V38 | `V38__add_outbox_event_version.sql` | Adds positive `event_version` metadata to outbox events with a default version of `1` for existing and new rows. |
| V39-V47 | `V39__...sql` through `V47__...sql` | Adds checkout/reservation recovery, append-only administration evidence, and Stripe conflict evidence. See the migration files for the authoritative SQL. |
| V48 | `V48__normalize_product_image_gallery_order.sql` | Backfills deterministic product-image positions, makes the position required, and replaces the gallery lookup index. |
| V49 | `V49__snapshot_product_review_author_name.sql` | Adds and backfills an optional review-author snapshot used when a user is retired. |
| V50 | `V50__add_user_credential_version.sql` | Adds a non-null credential version with a default of zero. |

## Rolling schema compatibility boundary

Restore compatibility and rolling schema compatibility are different claims. A restore rehearsal proves that historical
data can be restored and migrated forward for the current application. Rolling compatibility means the immediately
previous supported application can continue serving against the database after the candidate's migrations have run.
Neither claim proves the other.

Production startup applies Flyway through the migration identity before Hibernate `validate`, application readiness,
and traffic admission. Because a deployment can retain an old ready replica while starting a replacement, the first new
replica can migrate the shared database while the old revision is still serving. The repository therefore requires every
new migration to make a durable explicit decision in `.github/database-migration-compatibility.json`. PR CI compares the
candidate with an isolated checkout of the exact PR base repository and SHA, rejects changed or deleted historical
migrations, and fails closed when a new migration has no classification, rationale, previous-revision evidence plan, or
owner review. This is a governance boundary, not an automated proof that arbitrary SQL is safe.

### Classification contract

| Change | Minimum classification |
| --- | --- |
| Nullable columns, or columns with an old-writer-safe default | Potentially `rolling-safe`; review reads, writes, ORM validation, locks, and data semantics. |
| Indexes and non-validating constraints | Potentially `rolling-safe`; production build/lock duration remains deployment-owned. |
| Backfills, changed defaults, grants/ownership, or trigger/function replacement | `owner-review`; old-reader and old-writer semantics and privilege behavior need explicit evidence. |
| Required columns | `expand-contract`: add nullable/defaulted storage, deploy compatible readers/writers, backfill, then enforce in a later release. A one-step default plus `NOT NULL` is rolling-safe only with explicit evidence that old inserts remain valid. |
| Renames, destructive drops, incompatible type changes, enum/status narrowing | `expand-contract`; retain the old representation until no supported old revision uses it. |
| Transformations that invalidate old application assumptions | `expand-contract` when dual behavior is possible; otherwise `coordinated-maintenance`. |
| Changes that cannot preserve simultaneous old/new behavior | `coordinated-maintenance`; stop traffic/workers and do not describe the release as ordinary rolling-safe. |

An additive statement is not safe merely because it contains `ADD`. Unique/check constraints can reject writes, index
creation can block, defaults can change old-write meaning, triggers can change behavior, and a backfill can invalidate an
old reader's assumptions. SQL text classification is intentionally not used as a substitute for owner review.

### Evidence and limitations

For a `rolling-safe` decision, the PR must describe representative synthetic data and operations, the previous
application's startup/readiness and affected operations after migration, candidate startup with Hibernate validation and
readiness, Flyway history/checksums, and migration-owner/runtime-role separation. When those checks cannot be automated
trustworthily, classify the change as `owner-review`, `expand-contract`, or `coordinated-maintenance`; do not infer safety
from a green current-version migration test.

V48 is compatible with its predecessor: it retained every image, normalized the existing integer `sort_order`, retained
the zero default, and the preceding mapping already wrote non-null primitive integer values. The index replacement can
still lock production work and therefore does not prove a deployment-owned duration bound.
V49 is compatible with its predecessor: the nullable snapshot and backfill do not remove the existing user relationship,
and old writes may leave the snapshot null. V50 is compatible with its predecessor because the required integer has a
database default for old inserts and does not narrow existing data. Current-version migration tests and restore rehearsal
prove the current application after V48-V50, not mixed-version production behavior; no historical mixed-version
rehearsal was recorded for those already-applied migrations.

Load-balancer timing, replica count, production SQL and lock duration, orchestration, and maintenance scheduling remain
deployment-owned. The repository owns application startup/readiness, migration ordering and identity, schema validation,
and the fail-closed review decision only; it does not invent a production topology or duration SLO.

## Rules for future migrations

- Add a new `V{next}__descriptive_name.sql` file for every schema change.
- Do not edit older migrations as part of ordinary feature work.
- Include data backfills before adding `NOT NULL`, uniqueness, or check constraints when existing rows may violate the new rule.
- Keep entity mappings, repository assumptions, and migration SQL in sync.
- Add or update narrow persistence/migration tests when introducing constraints, indexes, required columns, or schema objects.

## Timestamp note

The migration history contains both plain `TIMESTAMP` and `TIMESTAMP WITH TIME ZONE` columns. V19 and V20 use `TIMESTAMP WITH TIME ZONE` for outbox/notification lifecycle fields, while earlier migrations include plain `TIMESTAMP` fields such as Stripe webhook processing timestamps. This is the current schema history and should not be rewritten retroactively.
