ALTER TABLE outbox_events
    ADD COLUMN last_attempt_at TIMESTAMP WITH TIME ZONE;
