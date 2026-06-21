CREATE INDEX idx_outbox_events_status_last_attempt_at
    ON outbox_events (status, last_attempt_at);

CREATE INDEX idx_outbox_events_status_attempts
    ON outbox_events (status, attempts);
