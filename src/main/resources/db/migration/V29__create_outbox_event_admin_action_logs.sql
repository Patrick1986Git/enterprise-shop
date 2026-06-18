CREATE TABLE outbox_event_admin_action_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    outbox_event_id UUID NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    actor_email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    details TEXT
);

CREATE INDEX idx_outbox_event_admin_action_logs_event_id_created_at
    ON outbox_event_admin_action_logs (outbox_event_id, created_at);

CREATE INDEX idx_outbox_event_admin_action_logs_actor_email_created_at
    ON outbox_event_admin_action_logs (actor_email, created_at);
