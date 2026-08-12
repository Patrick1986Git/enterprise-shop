ALTER TABLE orders
    ADD COLUMN checkout_idempotency_key VARCHAR(128);

ALTER TABLE orders
    ADD CONSTRAINT uk_orders_user_checkout_idempotency_key
        UNIQUE (user_id, checkout_idempotency_key);
