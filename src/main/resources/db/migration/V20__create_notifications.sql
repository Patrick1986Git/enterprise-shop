CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    type VARCHAR(80) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    source_event_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT,

    CONSTRAINT chk_notifications_status_allowed CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX idx_notifications_status_created_at
    ON notifications (status, created_at);

CREATE INDEX idx_notifications_source_event_id
    ON notifications (source_event_id);
