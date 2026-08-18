ALTER TABLE reservation_expiration_work
    ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN failed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_recovered_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_recovered_by VARCHAR(255),
    ADD CONSTRAINT chk_reservation_expiration_work_recovery_count CHECK (recovery_count >= 0),
    ADD CONSTRAINT chk_reservation_expiration_work_recovery_audit CHECK (
        (recovery_count = 0 AND last_recovered_at IS NULL AND last_recovered_by IS NULL)
        OR (recovery_count > 0 AND last_recovered_at IS NOT NULL AND last_recovered_by IS NOT NULL)
    );

UPDATE reservation_expiration_work
SET failed_at = LEAST(next_attempt_at, CURRENT_TIMESTAMP)
WHERE status = 'FAILED' AND failed_at IS NULL;
