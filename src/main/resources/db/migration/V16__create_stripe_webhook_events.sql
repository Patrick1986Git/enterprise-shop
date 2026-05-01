CREATE TABLE stripe_webhook_events (
    id UUID PRIMARY KEY,
    stripe_event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP NOT NULL
);

ALTER TABLE stripe_webhook_events
    ADD CONSTRAINT uq_stripe_webhook_events_stripe_event_id UNIQUE (stripe_event_id);
