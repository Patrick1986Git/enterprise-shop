ALTER TABLE notifications DROP CONSTRAINT chk_notifications_status_allowed;
ALTER TABLE notifications
    ADD COLUMN claim_token UUID,
    ADD COLUMN claim_expires_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT chk_notifications_status_allowed
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    ADD CONSTRAINT chk_notifications_claim_consistent CHECK (
        (status = 'PROCESSING' AND claim_token IS NOT NULL AND claim_expires_at IS NOT NULL)
        OR (status <> 'PROCESSING' AND claim_token IS NULL AND claim_expires_at IS NULL)
    );

CREATE INDEX idx_notifications_claim_recovery
    ON notifications (claim_expires_at)
    WHERE status = 'PROCESSING';
