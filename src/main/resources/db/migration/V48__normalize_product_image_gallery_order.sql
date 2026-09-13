-- Historical versions wrote the same position for every image. Preserve all rows and
-- choose UUID order as the stable fallback where the historical sequence is unknown.
WITH normalized AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY product_id
               ORDER BY sort_order ASC NULLS LAST, id ASC
           ) - 1 AS position
    FROM product_images
)
UPDATE product_images AS image
SET sort_order = normalized.position
FROM normalized
WHERE image.id = normalized.id;

ALTER TABLE product_images
    ALTER COLUMN sort_order SET DEFAULT 0,
    ALTER COLUMN sort_order SET NOT NULL;

DROP INDEX idx_product_images_product_id;
CREATE INDEX idx_product_images_product_order
    ON product_images(product_id, sort_order, id);
