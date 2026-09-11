# Module map

## Business modules (`com.company.shop.module`)

| Module | HTTP API exposure | Primary responsibility |
| --- | --- | --- |
| `cart` | Authenticated shopper API | Current user's cart lifecycle and checkout cart snapshots. |
| `category` | Public catalog API + admin API | Category tree and catalog classification. |
| `order` | Authenticated shopper API + admin API + public webhook | Checkout, order reads, payment records, Stripe webhook handling, and order outbox. |
| `product` | Public catalog/review reads + authenticated reviews + admin API | Product browsing/search, stock reservation, reviews, and admin product management. |
| `system` | Public root probe + authenticated status API | Root API message and application status. |
| `user` | Authenticated profile API + admin API | Current user profile and admin user management. |
| `notification` | Admin-only API | Notification persistence, delivery processing, admin observability, manual requeue operations, and requeue audit logs. |

## Module details

### cart

Purpose: authenticated shopper cart lifecycle.

- HTTP APIs: `/api/v1/me/cart` and nested item operations.
- Owns `Cart`, `CartItem`, cart DTOs, mapper, repository, service, and cart-specific stock exceptions.
- Exposes `CartCheckoutFacade` internally so checkout can read and clear a user's cart without coupling order code to cart persistence internals.

### category

Purpose: category tree and catalog classification.

- Public HTTP APIs: category listing and slug lookup under `/api/v1/categories`.
- Admin HTTP APIs: create/read/update/delete under `/api/v1/admin/categories`.
- Owns category hierarchy validation and duplicate/slug exceptions.
- Owns category persistence access. Product creation and update resolve an assignable category through the narrow
  `ProductCategoryFacade`; product code must not access `CategoryRepository` directly.

### order

Purpose: checkout, order history, admin order listing, payment integration, and consistency events.

- Shopper HTTP APIs: `/api/v1/me/orders`, `/api/v1/me/orders/checkout`.
- Shared authenticated read API: `/api/v1/orders/{id}`.
- Admin HTTP API: `/api/v1/admin/orders`.
- Public HTTP webhook API: `/api/v1/webhooks/stripe`; service logic verifies Stripe signatures.
- Owns order/payment entities, checkout orchestration, payment processing exceptions, webhook idempotency records, and the `order/outbox` package.
- `order/outbox` is the module's internal DB-backed consistency/integration mechanism. Checkout records an `OrderPlaced` event; the processor later dispatches pending events to handlers by event type.

### product

Purpose: product browsing, search, reviews, stock reservation, and admin product management.

- Public HTTP APIs: product listing, search, slug lookup, category lookup, and review listing.
- Authenticated HTTP APIs: review creation/deletion.
- Admin HTTP APIs: create/read/update/delete under `/api/v1/admin/products`.
- Owns product aggregate, review model, image model, specification-based querying, and product-specific invariants.
- Exposes `ProductCatalogFacade` internally so checkout can reserve product stock and read checkout product snapshots through a narrow contract.
- Retains the existing `Product` to `Category` lazy JPA relationship and database foreign key. The category facade
  deliberately returns the managed category entity needed by that relational model; it is a module ownership boundary,
  not an attempt to split the monolith or replace referential integrity with application validation.

### system

Purpose: lightweight application probes.

- HTTP APIs: public `/api/v1`; authenticated `/api/v1/system/status`.
- Does not own business persistence.

### user

Purpose: authenticated user profile and admin user management.

- Authenticated HTTP API: `/api/v1/me`.
- Admin HTTP APIs: `/api/v1/admin/users`.
- Authentication HTTP APIs are implemented in the `security` package under `/api/v1/auth` but use user DTOs and services.
- Exposes `CurrentUserFacade` internally so checkout can capture the current user's id/email snapshot without depending on web/security details.

### notification

Purpose: notification records, delivery processing, admin observability, manual requeue operations, and requeue audit logs.

- Handles `OrderPlaced` outbox events and creates `ORDER_PLACED_EMAIL` notification records.
- Admin-only HTTP route ownership: `/api/v1/admin/notifications` and `/api/v1/admin/notification-actions`.
- Authorization is restricted to administrators through the existing security configuration and method-level authorization.
- The admin API provides notification visibility, summaries, detail access, filtering, requeue operations, and action-log visibility.
- Delivery uses the `NotificationSender` abstraction. `NoopNotificationSender` is the fallback when no other sender bean is configured.
- `SmtpNotificationSender` is selected when `app.notification.smtp.enabled=true`; SMTP transport settings use Spring Boot `spring.mail.*` configuration and the sender address comes from `app.notification.smtp.from`.
- Scheduled delivery processing is controlled separately by `app.notification.delivery.enabled`.

## Audited cross-module boundaries

Production business-module dependencies are intentionally divided into two forms:

- Narrow internal APIs (`cart.api.internal`, `category.api.internal`, `product.api.internal`, and `user.api.internal`)
  are the permitted orchestration boundary for checkout and product category assignment.
- JPA entity references remain where the current relational model requires them: cart owns associations to user and
  product, product owns its category association, and product reviews associate to users. Order items do not associate
  to products; they persist product id, name, SKU, and price snapshots.

The remaining direct foreign repository access is cart's access to `ProductRepository` for live stock validation.
Cart also calls `UserService`, and product review calls `UserService`; these flows return managed user entities required
by their current JPA associations. They remain explicit architecture debt for a later transaction- and locking-aware
change. The order module reaches cart, product, and user only through their internal APIs. Notification consumes the
order-owned outbox handler contract and event payload. Security and authentication intentionally own cross-cutting
access to user authentication persistence and are not business-module-to-business-module dependencies.

## Cross-cutting packages

| Package | Responsibility |
| --- | --- |
| `common` | `ApiError`, `BusinessException`, global exception handling, base entities, i18n service, and request-id filter. |
| `config` | Security, OpenAPI, auditing, scheduling, i18n, and SQL function configuration. |
| `security` | Auth controller/service, JWT support, role constants, current-user provider, and startup role validation. |
| `validation` | Custom validation annotations and validators. |
