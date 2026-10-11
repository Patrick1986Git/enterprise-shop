-- Run manually as the local PostgreSQL administrator after Flyway V51.
-- Create the dedicated LOGIN and its protected password file separately; no password is accepted here.
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('shop.local_operator', :'operator_login', true) AS operator_context,
    set_config('shop.local_runtime', :'runtime_login', true) AS runtime_context,
    set_config('shop.local_migration', :'migration_login', true) AS migration_context,
    set_config('shop.local_database', :'expected_database', true) AS database_context \gset
DO $$
DECLARE operator_name NAME := current_setting('shop.local_operator');
BEGIN
    IF current_database() <> current_setting('shop.local_database')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = session_user AND rolsuper)
        OR operator_name IN (current_setting('shop.local_runtime'), current_setting('shop.local_migration'), session_user)
        OR current_setting('shop.local_runtime') = current_setting('shop.local_migration')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = operator_name AND rolcanlogin
            AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls)
        OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = operator_name))
        OR EXISTS (SELECT 1 FROM pg_shdepend WHERE refclassid = 'pg_authid'::regclass
            AND refobjid = (SELECT oid FROM pg_roles WHERE rolname = operator_name) AND deptype = 'o')
        OR current_setting('shop.local_migration') <> pg_get_userbyid(
            (SELECT nspowner FROM pg_namespace WHERE nspname = 'administrator_lifecycle')) THEN
        RAISE EXCEPTION 'Local database identity separation is invalid' USING ERRCODE = '42501';
    END IF;
    IF EXISTS (SELECT 1 FROM administrator_lifecycle.operator_identity WHERE operator_login <> operator_name) THEN
        RAISE EXCEPTION 'Revoke the previous operator before configuring another' USING ERRCODE = '42501';
    END IF;
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public, administrator_lifecycle FROM %I', operator_name);
    EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public, administrator_lifecycle FROM %I', operator_name);
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), operator_name);
    EXECUTE format('GRANT USAGE ON SCHEMA administrator_lifecycle TO %I', operator_name);
    EXECUTE format('GRANT EXECUTE ON FUNCTION administrator_lifecycle.promote_zero_administrator(UUID,TEXT,TEXT) TO %I', operator_name);
    EXECUTE format('GRANT SELECT ON administrator_lifecycle.promotion_evidence TO %I', operator_name);
    INSERT INTO administrator_lifecycle.operator_identity (operator_login, database_name, environment_name)
        VALUES (operator_name, current_database(), 'local')
        ON CONFLICT (singleton) DO UPDATE SET database_name = EXCLUDED.database_name;
END;
$$;
COMMIT;
