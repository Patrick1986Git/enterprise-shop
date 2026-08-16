ALTER TABLE orders
    ADD COLUMN reservation_expires_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_orders_due_reservation_expiration
    ON orders (reservation_expires_at, id)
    WHERE status = 'NEW' AND deleted = FALSE AND reservation_expires_at IS NOT NULL;

CREATE TABLE reservation_expiration_work (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id UUID NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claim_token UUID,
    claim_until TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_reservation_expiration_work_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT chk_reservation_expiration_work_status CHECK (status IN ('PENDING', 'CLAIMED', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_reservation_expiration_work_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_reservation_expiration_work_claim CHECK (
        (status = 'CLAIMED' AND claim_token IS NOT NULL AND claim_until IS NOT NULL)
        OR (status <> 'CLAIMED' AND claim_token IS NULL AND claim_until IS NULL)
    )
);

CREATE INDEX idx_reservation_expiration_work_due
    ON reservation_expiration_work (next_attempt_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_reservation_expiration_work_claim_lease
    ON reservation_expiration_work (claim_until, id)
    WHERE status = 'CLAIMED';
