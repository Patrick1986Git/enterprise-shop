# Product concurrency and field ownership

Administrative product creation owns initial stock as well as catalog fields. After creation, stock is inventory-owned: checkout reservation decrements it and reservation release restores it while holding the product row lock. Administrative catalog updates cannot submit stock and therefore cannot resurrect a completed reservation or discard a restoration.

`PUT /api/v1/admin/products/{id}` owns name, generated slug, SKU, description, price, category, and the complete ordered image gallery. It requires the `version` returned by product reads. The service locks the product row, then rejects a different version with `409 Conflict` and `PRODUCT_UPDATE_CONFLICT`. A command based on a snapshot predating an inventory, review-aggregate, deletion, or catalog scalar update must reload before retrying. Unknown request fields, including the former `stock` field, are rejected rather than ignored.

Checkout reservation, inventory restoration, review aggregation, and administrative catalog update use the same pessimistic product-row serialization boundary. JPA `@Version` remains the database backstop for product scalar changes; the HTTP version precondition additionally detects payloads that became stale before a request began. Review-derived `averageRating` and `reviewCount` are not part of the administrative command.

Gallery replacement remains atomic with the catalog update. Images retain zero-based `sort_order`, are read by `sortOrder ASC, id ASC`, and the first image remains the main image, including the deterministic legacy tie-break.
