ALTER TABLE reservation_expiration_work
    ADD COLUMN recovery_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_reservation_expiration_work_recovery_authorized CHECK (
        recovery_authorized = FALSE OR status = 'PENDING'
    );
