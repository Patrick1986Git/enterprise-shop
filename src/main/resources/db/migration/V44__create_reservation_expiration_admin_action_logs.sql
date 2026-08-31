CREATE TABLE reservation_expiration_admin_action_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id UUID NOT NULL,
    work_id UUID NOT NULL,
    action_type VARCHAR(30) NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    actor_email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_reservation_expiration_admin_action_type
        CHECK (action_type IN ('LEGACY_ADOPTION', 'RECOVERY')),
    CONSTRAINT chk_reservation_expiration_admin_action_outcome
        CHECK ((action_type = 'LEGACY_ADOPTION' AND outcome = 'ADOPTED')
            OR (action_type = 'RECOVERY' AND outcome IN ('REQUEUED', 'TERMINAL_NOOP')))
);

CREATE INDEX idx_reservation_expiration_admin_actions_order_created
    ON reservation_expiration_admin_action_logs (order_id, created_at DESC, id DESC);
CREATE INDEX idx_reservation_expiration_admin_actions_work_created
    ON reservation_expiration_admin_action_logs (work_id, created_at DESC, id DESC);
CREATE INDEX idx_reservation_expiration_admin_actions_actor_created
    ON reservation_expiration_admin_action_logs (actor_email, created_at DESC, id DESC);
