CREATE TABLE stripe_payment_conflicts (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    stripe_event_id VARCHAR(255) NOT NULL,
    provider_payment_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    order_status VARCHAR(32) NOT NULL,
    payment_status VARCHAR(32) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_stripe_payment_conflicts_event UNIQUE (stripe_event_id),
    CONSTRAINT ck_stripe_payment_conflicts_event_type CHECK (event_type = 'payment_intent.succeeded'),
    CONSTRAINT ck_stripe_payment_conflicts_order_status CHECK (order_status IN ('PAID', 'SHIPPED', 'CANCELLED')),
    CONSTRAINT ck_stripe_payment_conflicts_payment_status CHECK (payment_status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_stripe_payment_conflicts_reason CHECK (reason = 'TERMINAL_STATE_CONTRADICTION')
);

CREATE INDEX idx_stripe_payment_conflicts_observed_id
    ON stripe_payment_conflicts (observed_at DESC, id);

CREATE FUNCTION reject_runtime_stripe_payment_conflict_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    table_owner NAME;
BEGIN
    SELECT pg_get_userbyid(relowner) INTO table_owner FROM pg_class WHERE oid = TG_RELID;
    IF session_user = table_owner THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'stripe payment conflicts are append-only for runtime roles' USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER stripe_payment_conflicts_append_only
    BEFORE UPDATE OR DELETE ON stripe_payment_conflicts
    FOR EACH ROW EXECUTE FUNCTION reject_runtime_stripe_payment_conflict_mutation();
