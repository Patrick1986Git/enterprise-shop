CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,

    CONSTRAINT chk_outbox_events_status_allowed CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    CONSTRAINT chk_outbox_events_attempts_non_negative CHECK (attempts >= 0)
);

CREATE INDEX idx_outbox_events_status_created_at
    ON outbox_events (status, created_at);

CREATE INDEX idx_outbox_events_aggregate
    ON outbox_events (aggregate_type, aggregate_id);
