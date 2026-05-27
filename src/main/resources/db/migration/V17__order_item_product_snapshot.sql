ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS product_name VARCHAR(255);

ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS product_sku VARCHAR(50);

UPDATE order_items oi
SET product_name = p.name
FROM products p
WHERE oi.product_id = p.id
  AND oi.product_name IS NULL;

UPDATE order_items oi
SET product_sku = p.sku
FROM products p
WHERE oi.product_id = p.id
  AND oi.product_sku IS NULL;

ALTER TABLE order_items
    ALTER COLUMN product_name SET NOT NULL;

ALTER TABLE order_items
    ALTER COLUMN product_sku SET NOT NULL;
