CREATE TABLE notification_admin_action_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    notification_id UUID NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    actor_email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    details TEXT
);

CREATE INDEX idx_notification_admin_action_logs_notification_id_created_at
    ON notification_admin_action_logs (notification_id, created_at);

CREATE INDEX idx_notification_admin_action_logs_actor_email_created_at
    ON notification_admin_action_logs (actor_email, created_at);
