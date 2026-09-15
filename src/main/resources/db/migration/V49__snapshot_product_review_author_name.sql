ALTER TABLE product_reviews ADD COLUMN author_name VARCHAR(201);

UPDATE product_reviews reviews
SET author_name = COALESCE(
    NULLIF(BTRIM(CONCAT_WS(' ', users.first_name, users.last_name)), ''),
    'Anonymous'
)
FROM users
WHERE users.id = reviews.user_id;

ALTER TABLE product_reviews ALTER COLUMN author_name SET NOT NULL;
ALTER TABLE product_reviews ADD CONSTRAINT ck_product_reviews_author_name_not_blank
    CHECK (BTRIM(author_name) <> '');
