ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN dead_letter_reason TEXT;

ALTER TABLE outbox_events
    DROP CONSTRAINT chk_outbox_events_status_allowed;

ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_status_allowed
        CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED', 'DEAD_LETTER'));

CREATE INDEX idx_outbox_events_status_next_attempt_at
    ON outbox_events (status, next_attempt_at);
