CREATE TABLE stripe_payment_conflict_dispositions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    conflict_id UUID NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    actor_email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stripe_conflict_dispositions_conflict
        FOREIGN KEY (conflict_id) REFERENCES stripe_payment_conflicts (id),
    CONSTRAINT ck_stripe_conflict_dispositions_action
        CHECK (action_type IN ('ACKNOWLEDGED', 'ESCALATED'))
);

CREATE INDEX idx_stripe_conflict_dispositions_conflict_created
    ON stripe_payment_conflict_dispositions (conflict_id, created_at DESC, id ASC);

CREATE TRIGGER stripe_payment_conflict_dispositions_append_only
    BEFORE UPDATE OR DELETE ON stripe_payment_conflict_dispositions
    FOR EACH ROW
    EXECUTE FUNCTION reject_runtime_admin_action_log_mutation();
