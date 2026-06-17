ALTER TABLE outbox_events
    ADD COLUMN requeue_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_requeued_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_requeued_by VARCHAR(255);
