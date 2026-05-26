ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS product_name VARCHAR(255);

UPDATE order_items oi
SET product_name = p.name
FROM products p
WHERE oi.product_id = p.id
  AND oi.product_name IS NULL;

ALTER TABLE order_items
    ALTER COLUMN product_name SET NOT NULL;
