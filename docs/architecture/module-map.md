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
| `notification` | Internal only | Notification records and delivery baseline created from outbox events. |

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

Purpose: internal notification records and delivery baseline.

- No HTTP API is exposed by this module.
- Handles `OrderPlaced` outbox events and creates `ORDER_PLACED_EMAIL` notification records.
- Delivery uses the `NotificationSender` abstraction. The current sender implementation is `NoopNotificationSender`, which logs that delivery is skipped; there is no external email/SMS provider configured in the codebase.

## Cross-cutting packages

| Package | Responsibility |
| --- | --- |
| `common` | `ApiError`, `BusinessException`, global exception handling, base entities, i18n service, and request-id filter. |
| `config` | Security, OpenAPI, auditing, scheduling, i18n, and SQL function configuration. |
| `security` | Auth controller/service, JWT support, role constants, current-user provider, and startup role validation. |
| `validation` | Custom validation annotations and validators. |
