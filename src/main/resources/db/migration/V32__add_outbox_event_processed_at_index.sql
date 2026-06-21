CREATE INDEX idx_outbox_events_status_processed_at
    ON outbox_events (status, processed_at);
