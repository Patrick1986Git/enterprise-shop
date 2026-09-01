CREATE FUNCTION reject_runtime_admin_action_log_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    table_owner NAME;
BEGIN
    SELECT pg_get_userbyid(relowner)
    INTO table_owner
    FROM pg_class
    WHERE oid = TG_RELID;

    IF session_user = table_owner THEN
        RETURN OLD;
    END IF;

    RAISE EXCEPTION 'admin action log % is append-only for runtime roles', TG_TABLE_NAME
        USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER notification_admin_action_logs_append_only
    BEFORE UPDATE OR DELETE ON notification_admin_action_logs
    FOR EACH ROW
    EXECUTE FUNCTION reject_runtime_admin_action_log_mutation();

CREATE TRIGGER outbox_event_admin_action_logs_append_only
    BEFORE UPDATE OR DELETE ON outbox_event_admin_action_logs
    FOR EACH ROW
    EXECUTE FUNCTION reject_runtime_admin_action_log_mutation();

CREATE TRIGGER reservation_expiration_admin_action_logs_append_only
    BEFORE UPDATE OR DELETE ON reservation_expiration_admin_action_logs
    FOR EACH ROW
    EXECUTE FUNCTION reject_runtime_admin_action_log_mutation();
