# Product concurrency and field ownership

Administrative product creation owns initial stock as well as catalog fields. After creation, stock is inventory-owned: checkout reservation decrements it and reservation release restores it while holding the product row lock. Administrative catalog updates cannot submit stock and therefore cannot resurrect a completed reservation or discard a restoration.

`PUT /api/v1/admin/products/{id}` owns name, generated slug, SKU, description, price, category, and the complete ordered image gallery. Its aggregate concurrency token is the `version` returned by product reads. The service locks the product row, rejects a different version with `409 Conflict` and `PRODUCT_UPDATE_CONFLICT`, and only then asks JPA for a pessimistic force increment. Every successful catalog PUT—including a gallery-only change—therefore consumes the submitted token and returns its successor. A rejected or rolled-back request does not consume a token. Unknown request fields, including the former `stock` field, are rejected rather than ignored.

Checkout reservation, inventory restoration, review aggregation, and administrative catalog update use the same pessimistic product-row serialization boundary. Force increment is scoped to the ADMIN catalog operation because inverse gallery child changes do not independently dirty the Product row. JPA `@Version` remains the database backstop for other Product scalar changes; the HTTP version precondition additionally detects payloads that became stale before a request began. Review-derived `averageRating` and `reviewCount` are not part of the administrative command.

Gallery replacement remains atomic with the catalog update. Images retain zero-based `sort_order`, are read by `sortOrder ASC, id ASC`, and the first image remains the main image, including the deterministic legacy tie-break.

## Product retirement lifecycle

`DELETE /api/v1/admin/products/{id}` is an authoritative, serialized retirement command. It does not require the
catalog version supplied to PUT. The service obtains the same pessimistic Product-row lock used by catalog updates,
checkout reservation, inventory restoration, and review aggregation, then sets `deleted` and `deleted_at`. JPA advances
the Product version when that scalar change commits. A second DELETE and all active Product reads return the existing
`PRODUCT_NOT_FOUND` contract. Rollback preserves visibility, timestamps, stock, ratings, gallery, and version.

The row lock makes races deterministic: an operation that locked the active row first may commit before retirement;
after retirement commits, active locked lookups cannot reserve inventory, accept a catalog PUT, or create/recompute a
review. Retirement never mutates stock or Category. Multi-product checkout and restoration continue to acquire Product
rows in UUID order. A reservation made before retirement remains valid, and release locks and restores the physical
hidden row exactly once, increments its version, and never clears `deleted`.

Cart items deliberately retain their physical Product foreign key. After retirement, cart reads retain the line and its
identifier, report `available=false`, null current catalog/price fields, zero subtotal and exclude it from the cart total;
GET does not mutate the cart. Quantity changes fail through the active Product lookup with `PRODUCT_NOT_FOUND`, explicit
removal remains possible, and checkout carries the retained identifier to the active locked lookup and fails atomically
before an Order or Payment is committed. Existing Order items are independent historical snapshots of Product identity,
name, SKU and price, so later retirement does not change order history. Reviews and images remain retained by their
foreign keys; retirement does not hard-delete or cascade them. Provider calls occur only after the checkout transaction
commits, so a retired cart Product cannot cause partial provider/payment creation.
