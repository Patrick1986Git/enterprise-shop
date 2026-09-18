# Soft deletion and business-identifier reservation

Soft deletion, query visibility, physical retention, and identifier reservation are separate concerns. In this
application, `deleted = true` makes an entity invisible to ordinary Hibernate reads when the entity has an
`@SQLRestriction`, but it does not remove the PostgreSQL row. A global unique constraint or index still considers the
retained row. A partial unique index would be a separate, explicit reuse policy; soft deletion alone does not imply it.

This record inventories the production schema and supported lifecycle paths. It uses these classifications:

- **A — intentionally permanently reserved:** repository evidence requires reuse to remain prohibited.
- **B — intentionally reusable after retirement:** repository evidence requires reclamation after retirement.
- **C — policy not established:** the current behavior is known, but the repository does not contain enough product or
  domain evidence to change it safely.

## Inventory and decisions

| Entity / identifier | PostgreSQL uniqueness | Retired-row visibility and reuse behavior | Evidence and classification |
| --- | --- | --- | --- |
| `users.email` (normalized email) | `users_email_key` on `(email)` and `ux_users_email_lower` on `(lower(email))`; both global | `User` is soft deleted and hidden from ordinary ORM reads. Registration inserts a new row, so either retained-email index can reject reuse and the API maps that conflict to the existing-user response. | **A.** The normalized email is the JWT subject and active-user lookup key. Permanent reservation is an authentication and retired-token replay-safety invariant; a retired email must not be re-registered while that identity model remains in use. |
| `products.sku` | `uq_products_sku` on `(sku)`; global | `Product` is soft deleted and hidden. Create-time repository checks cannot see a retired collision; PostgreSQL rejects the insert and the service maps the named constraint to the SKU conflict. | **C.** SKU reuse after catalog retirement is not defined. Historical order lines retain product snapshots, but the repository does not state whether that requires permanent SKU reservation. |
| `products.slug` | `uq_products_slug` on `(slug)`; global | `Product` is soft deleted and hidden. Slug generation does not see retired rows, so a generated collision reaches PostgreSQL and is mapped to a slug conflict rather than selecting a suffix. | **C.** No durable URL/tombstone or reclaim policy is defined. |
| `categories.name` | `categories_name_key` on `(name)`; global | `Category` is soft deleted and hidden. Re-creation does not see a retired name; the physical constraint rejects it and the service returns the category-name conflict. | **C.** Retirement protects active assignments, but no name-reservation policy is established. |
| `categories.slug` | `categories_slug_key` on `(slug)`; global | `Category` is soft deleted and hidden. Re-creation does not see a retired slug; the physical constraint rejects it and the service returns the slug conflict. | **C.** No durable URL/tombstone or reclaim policy is defined. |
| `product_reviews(product_id, user_id)` | `uk_user_product_review` on `(product_id, user_id)`; global | `ProductReview` is soft deleted and hidden. Delete preserves the physical row and removes it from rating statistics. A later ordinary existence check sees no review and an insert is attempted; PostgreSQL rejects it and the service returns `PRODUCT_REVIEW_ALREADY_EXISTS`. | **C.** The system clearly enforces one physical review per user/product, but does not define whether deletion is permanent withdrawal, whether restoration is allowed, or whether a later review is a new historical event. |
| `discount_codes.code` | `discount_codes_code_key` on `(code)`; global. `idx_discount_codes_code` is a non-unique partial lookup index on `(code) WHERE active = true AND deleted = false`. | `DiscountCode` is soft deleted and hidden, while the global constraint continues to reserve the code. There is no production create or retire service/API path; checkout only looks up an existing active row. | **C.** The schema describes the code as customer-facing and unique, but does not establish a reuse policy. The partial lookup index does not make the identifier reusable. |
| `orders(user_id, checkout_idempotency_key)` | `uk_orders_user_checkout_idempotency_key` on `(user_id, checkout_idempotency_key)`; global | `Order` has ORM soft-delete filtering, but no supported production order-deletion path. Checkout resolves the same key to the already-created order and the physical key remains reserved. | **A for reachable behavior.** Permanent reservation is the checkout idempotency invariant. Soft-delete reuse is currently unreachable and must not be introduced without redefining idempotency retention. |
| `payments.order_id` | `uq_payments_order_id` on `(order_id)`; global | `Payment` has ORM soft-delete filtering, but no supported production payment-deletion path. Initialization finds or creates the single payment for an order; a retained row would still prevent a second payment record. | **A for reachable behavior.** One payment record per order supports payment/provider reconciliation. Soft-delete reuse is currently unreachable. `provider_payment_id` has only the non-unique `idx_payments_provider_payment_id`. |

All listed uniqueness rules are global; there is no production partial **unique** index for these identifiers. Consequently,
all retained soft-deleted rows participate in uniqueness checks. `Order.id`, `Payment.id`, and provider-generated values do
not create another reachable soft-delete/recreate interaction: entity UUID primary keys identify retained history, and
the current schema does not uniquely constrain `payments.provider_payment_id`.

## Product-review lifecycle finding

The supported delete path authorizes the owner or an administrator, locks the Product row, marks the review deleted,
and recalculates the Product aggregate from visible reviews. The retained review then has `deleted = true` and a
non-null `deleted_at`; ordinary repository reads and the public review listing do not return it. The same active User's
next create request passes the ORM-visible existence check and attempts a second physical row. The global
`uk_user_product_review` constraint wins, the transaction rolls back, and the service translates that named database
conflict to the sanitized `PRODUCT_REVIEW_ALREADY_EXISTS` domain conflict. The original row remains the only physical
row and the Product remains at zero reviews and an average rating of `0.0` when it has no other reviews.

This behavior is covered by a PostgreSQL lifecycle test, including catalog observation of the winning constraint,
physical retired-row state, ORM invisibility, attempted recreation, row count, and final aggregate. The evidence proves
the behavior but not the desired product policy, so no migration or production behavior change is justified. In
particular, replacing the constraint with a partial unique index, restoring the old row, or hard-deleting it would each
make an unrecorded decision about audit identity, timestamps, author snapshots, and aggregate history.

## Decision ownership for Class C identifiers

- Product/catalog owners should decide whether SKU identifies an immutable catalog lineage and whether retired public
  slugs remain tombstones or may be reclaimed.
- Category/catalog owners should decide whether retired taxonomy names and URLs may be reused.
- Promotions and finance owners should decide discount-code retention and reuse requirements, including historical
  redemption and support implications, before a production administration lifecycle is added.
- Product/community and compliance owners should jointly define whether deleting a review means permanent withdrawal,
  restorable history, or permission to publish a distinct later review. That decision must define review identity,
  timestamps, author snapshots, moderation/audit retention, and concurrent delete/create behavior.

Until those decisions are recorded, Class C identifiers retain their current global constraints. A future change must
use a forward-only migration, define the public conflict semantics, and add deterministic PostgreSQL concurrency proof.
