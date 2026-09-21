\set ON_ERROR_STOP on

-- Deterministic, obviously synthetic recovery markers. This is operational fixture data,
-- not an application bootstrap path and never contains production-derived values.
INSERT INTO users (id, email, password, first_name, last_name, credential_version)
VALUES ('10000000-0000-0000-0000-000000000001', 'restore-rehearsal@example.invalid',
        '$synthetic-not-a-login-secret$', 'Restore', 'Rehearsal', 7);

INSERT INTO user_roles (user_id, role_id)
SELECT '10000000-0000-0000-0000-000000000001', id FROM roles WHERE name = 'ROLE_USER';

INSERT INTO categories (id, name, slug, description)
VALUES ('20000000-0000-0000-0000-000000000001', 'Synthetic recovery category',
        'synthetic-recovery-category', 'żółty synthetic restore marker');

INSERT INTO products (id, sku, slug, name, description, price, stock, category_id, version)
VALUES ('30000000-0000-0000-0000-000000000001', 'SYNTHETIC-RESTORE-SKU',
        'synthetic-restore-product', 'Żółty synthetic product', 'portable logical restore marker',
        123.45, 37, '20000000-0000-0000-0000-000000000001', 11);

INSERT INTO product_images (id, product_id, image_url, sort_order)
VALUES ('31000000-0000-0000-0000-000000000001',
        '30000000-0000-0000-0000-000000000001',
        'https://example.invalid/synthetic-restore-image.png', 0);

INSERT INTO carts (id, user_id)
VALUES ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001');
INSERT INTO cart_items (id, cart_id, product_id, quantity)
VALUES ('41000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
        '30000000-0000-0000-0000-000000000001', 2);

INSERT INTO orders (id, user_id, user_email, status, total_amount, checkout_idempotency_key,
                    reservation_expires_at)
VALUES ('50000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        'restore-snapshot@example.invalid', 'NEW', 123.45, 'synthetic-restore-checkout-key',
        '2040-01-02 03:04:05+00');
INSERT INTO order_items (id, order_id, product_id, quantity, price, product_name, product_sku)
VALUES ('51000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001',
        '30000000-0000-0000-0000-000000000001', 1, 123.45,
        'Immutable synthetic product snapshot', 'SYNTHETIC-SNAPSHOT-SKU');

INSERT INTO payments (id, order_id, payment_method, status, amount, provider_payment_id, client_secret)
VALUES ('60000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001',
        'STRIPE', 'PENDING', 123.45, 'pi_synthetic_restore_only', 'synthetic_client_secret_not_real');
INSERT INTO stripe_webhook_events (id, stripe_event_id, event_type, processed_at)
VALUES ('61000000-0000-0000-0000-000000000001', 'evt_synthetic_restore_only',
        'payment_intent.synthetic', '2040-01-02 03:04:06');

INSERT INTO reservation_expiration_work
  (id, order_id, status, due_at, next_attempt_at, attempts, recovery_count,
   last_recovered_at, last_recovered_by, recovery_authorized)
VALUES ('70000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001',
        'PENDING', '2040-01-02 03:04:05+00', '2040-01-02 03:04:05+00', 3, 1,
        '2040-01-02 02:00:00+00', 'restore-rehearsal@example.invalid', true);

INSERT INTO outbox_events
  (id, aggregate_type, aggregate_id, event_type, payload, status, attempts, last_error,
   next_attempt_at, requeue_count, last_requeued_at, last_requeued_by, last_attempt_at, event_version)
VALUES ('80000000-0000-0000-0000-000000000001', 'Order',
        '50000000-0000-0000-0000-000000000001', 'SYNTHETIC_RESTORE',
        '{"synthetic":true,"marker":"restore-rehearsal"}'::jsonb, 'FAILED', 4,
        'synthetic failure', '2040-01-03 00:00:00+00', 2, '2040-01-02 04:00:00+00',
        'restore-rehearsal@example.invalid', '2040-01-02 03:30:00+00', 5);

INSERT INTO notifications
  (id, type, recipient, subject, body, status, source_event_id, attempts, next_attempt_at,
   last_attempt_at, requeue_count, last_requeued_at, last_requeued_by)
VALUES ('90000000-0000-0000-0000-000000000001', 'SYNTHETIC_RESTORE',
        'restore-rehearsal@example.invalid', 'Synthetic restore', 'Synthetic notification body',
        'FAILED', '80000000-0000-0000-0000-000000000001', 6, '2040-01-03 00:00:00+00',
        '2040-01-02 03:45:00+00', 2, '2040-01-02 04:00:00+00',
        'restore-rehearsal@example.invalid');

INSERT INTO notification_admin_action_logs
  (id, notification_id, action_type, actor_email, details, created_at)
VALUES ('a0000000-0000-0000-0000-000000000001', '90000000-0000-0000-0000-000000000001',
        'REQUEUE', 'restore-rehearsal@example.invalid', 'synthetic restore marker',
        '2040-01-02 05:00:00+00');
INSERT INTO outbox_event_admin_action_logs
  (id, outbox_event_id, action_type, actor_email, details, created_at)
VALUES ('a1000000-0000-0000-0000-000000000001', '80000000-0000-0000-0000-000000000001',
        'REQUEUE', 'restore-rehearsal@example.invalid', 'synthetic restore marker',
        '2040-01-02 05:00:00+00');
INSERT INTO reservation_expiration_admin_action_logs
  (id, order_id, work_id, action_type, outcome, actor_email, created_at)
VALUES ('a2000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001',
        '70000000-0000-0000-0000-000000000001', 'RECOVERY', 'REQUEUED',
        'restore-rehearsal@example.invalid', '2040-01-02 05:00:00+00');
