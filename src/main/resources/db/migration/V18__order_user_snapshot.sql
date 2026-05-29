ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS user_email VARCHAR(255);

UPDATE orders o
SET user_email = u.email
FROM users u
WHERE o.user_id = u.id
  AND o.user_email IS NULL;

ALTER TABLE orders
    ALTER COLUMN user_email SET NOT NULL;
