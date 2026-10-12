CREATE SCHEMA administrator_recovery;
REVOKE ALL ON SCHEMA administrator_recovery FROM PUBLIC;
-- Function EXECUTE defaults are global; explicitly revoke every function below rather than altering public defaults.

CREATE TABLE administrator_recovery.operator_identity (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    operator_login NAME NOT NULL,
    runtime_login NAME NOT NULL,
    migration_login NAME NOT NULL,
    bootstrap_login NAME NOT NULL,
    database_name NAME NOT NULL,
    environment_name TEXT NOT NULL CHECK (environment_name = 'local'),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (operator_login NOT IN (runtime_login, migration_login, bootstrap_login))
);

CREATE TABLE administrator_recovery.emergency_secrets (
    id UUID PRIMARY KEY,
    target_user_id UUID NOT NULL,
    database_name NAME NOT NULL,
    environment_name TEXT NOT NULL CHECK (environment_name = 'local'),
    verifier BYTEA NOT NULL CHECK (octet_length(verifier) = 32),
    enrolled_by NAME NOT NULL,
    enrolled_credential_version BIGINT NOT NULL CHECK (enrolled_credential_version >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CHECK (consumed_at IS NULL OR (isfinite(consumed_at) AND consumed_at >= created_at)),
    CHECK (revoked_at IS NULL OR (isfinite(revoked_at) AND revoked_at >= created_at)),
    CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);
CREATE UNIQUE INDEX ux_administrator_recovery_open_secret
    ON administrator_recovery.emergency_secrets(target_user_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE TABLE administrator_recovery.authorizations (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    secret_id UUID NOT NULL REFERENCES administrator_recovery.emergency_secrets(id) ON DELETE RESTRICT,
    target_user_id UUID NOT NULL,
    database_name NAME NOT NULL,
    environment_name TEXT NOT NULL CHECK (environment_name = 'local'),
    operator_login NAME NOT NULL,
    authorized_by NAME NOT NULL,
    expected_credential_version BIGINT NOT NULL CHECK (expected_credential_version >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at = created_at + INTERVAL '10 minutes'),
    CHECK (consumed_at IS NULL OR (isfinite(consumed_at) AND consumed_at >= created_at AND consumed_at < expires_at)),
    CHECK (revoked_at IS NULL OR (isfinite(revoked_at) AND revoked_at >= created_at)),
    CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);
CREATE UNIQUE INDEX ux_administrator_recovery_open_authorization
    ON administrator_recovery.authorizations(target_user_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE TABLE administrator_recovery.reset_evidence (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    authorization_id UUID NOT NULL UNIQUE,
    target_user_id UUID NOT NULL,
    operator_login NAME NOT NULL,
    database_name NAME NOT NULL,
    operation TEXT NOT NULL CHECK (operation = 'RESET_SOLE_ADMIN'),
    credential_version_before BIGINT NOT NULL CHECK (credential_version_before >= 0),
    credential_version_after BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp() CHECK (isfinite(recorded_at)),
    CHECK (credential_version_after = credential_version_before + 1)
);

CREATE FUNCTION administrator_recovery.require_owner()
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = session_user AND rolsuper)
        OR EXISTS (SELECT 1 FROM administrator_recovery.operator_identity WHERE operator_login = session_user) THEN
        RAISE EXCEPTION 'Trusted local owner session required' USING ERRCODE = '42501';
    END IF;
END;
$$;

-- These session settings prevent supported prepared inputs entering PostgreSQL statement/error logs.
CREATE FUNCTION administrator_recovery.require_sensitive_session()
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    IF system_user IS NULL OR system_user NOT IN ('scram-sha-256:' || session_user, 'md5:' || session_user)
        OR current_setting('log_statement') <> 'none'
        OR current_setting('log_min_duration_statement') <> '-1'
        OR current_setting('log_min_duration_sample') <> '-1'
        OR current_setting('log_transaction_sample_rate')::numeric <> 0
        OR current_setting('log_parameter_max_length') <> '0'
        OR current_setting('log_parameter_max_length_on_error') <> '0'
        OR current_setting('log_error_verbosity') <> 'terse'
        OR coalesce(current_setting('pgaudit.log', TRUE), '') NOT IN ('', 'none')
        OR coalesce(current_setting('auto_explain.log_min_duration', TRUE), '-1') <> '-1' THEN
        RAISE EXCEPTION 'Sensitive session logging policy is not configured' USING ERRCODE = '42501';
    END IF;
END;
$$;

CREATE FUNCTION administrator_recovery.require_context(p_database TEXT, p_environment TEXT)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Recovery requires READ COMMITTED' USING ERRCODE = '25001';
    END IF;
    IF p_database IS DISTINCT FROM current_database() OR p_environment IS DISTINCT FROM 'local'
        OR NOT EXISTS (SELECT 1 FROM administrator_recovery.operator_identity
            WHERE active AND database_name = current_database() AND environment_name = 'local') THEN
        RAISE EXCEPTION 'Local recovery context is invalid' USING ERRCODE = '42501';
    END IF;
END;
$$;

CREATE FUNCTION administrator_recovery.lock_target(p_target UUID, p_database TEXT, p_environment TEXT)
RETURNS public.users LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE target public.users%ROWTYPE; usable BIGINT; matches BOOLEAN;
BEGIN
    PERFORM administrator_recovery.require_context(p_database, p_environment);
    PERFORM pg_advisory_xact_lock(1094995278, 1);
    PERFORM pg_advisory_xact_lock(hashtextextended(p_target::TEXT, 0));
    PERFORM administrator_recovery.require_context(p_database, p_environment);
    SELECT count(*), bool_and(u.id = p_target) INTO usable, matches
        FROM public.users u WHERE u.enabled AND NOT u.deleted AND EXISTS
            (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                WHERE ur.user_id = u.id AND r.name = 'ROLE_ADMIN');
    IF usable <> 1 OR matches IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Recovery target is not the sole usable administrator' USING ERRCODE = '22023';
    END IF;
    SELECT * INTO target FROM public.users WHERE id = p_target FOR UPDATE;
    RETURN target;
END;
$$;

CREATE FUNCTION administrator_recovery.secret_verifier(p_id UUID, p_target UUID, p_database TEXT, p_secret BYTEA)
RETURNS BYTEA LANGUAGE sql IMMUTABLE SET search_path = pg_catalog AS $$
    SELECT sha256(convert_to('enterprise-shop/local-admin-emergency/v1' || chr(10) || p_id::text || chr(10)
        || p_target::text || chr(10) || p_database || chr(10) || 'local' || chr(10), 'UTF8') || p_secret);
$$;

CREATE FUNCTION administrator_recovery.verify_secret(p_id UUID, p_target UUID, p_database TEXT, p_secret BYTEA)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE stored BYTEA; candidate BYTEA; difference INTEGER := 0;
BEGIN
    SELECT verifier INTO stored FROM administrator_recovery.emergency_secrets
        WHERE id = p_id AND target_user_id = p_target AND database_name = p_database
            AND environment_name = 'local' AND consumed_at IS NULL AND revoked_at IS NULL FOR UPDATE;
    IF stored IS NULL OR p_secret IS NULL OR octet_length(p_secret) <> 32 THEN
        RAISE EXCEPTION 'Emergency proof rejected' USING ERRCODE = '42501';
    END IF;
    candidate := administrator_recovery.secret_verifier(p_id, p_target, p_database, p_secret);
    FOR i IN 0..31 LOOP difference := difference | (get_byte(stored, i) # get_byte(candidate, i)); END LOOP;
    IF difference <> 0 THEN
        RAISE EXCEPTION 'Emergency proof rejected' USING ERRCODE = '42501';
    END IF;
END;
$$;

CREATE FUNCTION administrator_recovery.guard_history()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Recovery history is retained' USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'UPDATE' AND (
        (to_jsonb(NEW) - 'consumed_at' - 'revoked_at') IS DISTINCT FROM (to_jsonb(OLD) - 'consumed_at' - 'revoked_at')
        OR ((OLD.consumed_at IS NOT NULL OR OLD.revoked_at IS NOT NULL)
            AND ROW(NEW.consumed_at, NEW.revoked_at) IS DISTINCT FROM ROW(OLD.consumed_at, OLD.revoked_at))) THEN
        RAISE EXCEPTION 'Recovery issuance and terminal state are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER emergency_secrets_serialize BEFORE INSERT OR UPDATE OR DELETE
    ON administrator_recovery.emergency_secrets FOR EACH STATEMENT
    EXECUTE FUNCTION administrator_lifecycle.serialize_transition();
CREATE TRIGGER authorizations_serialize BEFORE INSERT OR UPDATE OR DELETE
    ON administrator_recovery.authorizations FOR EACH STATEMENT
    EXECUTE FUNCTION administrator_lifecycle.serialize_transition();
CREATE TRIGGER emergency_secrets_history BEFORE UPDATE OR DELETE ON administrator_recovery.emergency_secrets
    FOR EACH ROW EXECUTE FUNCTION administrator_recovery.guard_history();
CREATE TRIGGER emergency_secrets_no_truncate BEFORE TRUNCATE ON administrator_recovery.emergency_secrets
    FOR EACH STATEMENT EXECUTE FUNCTION administrator_recovery.guard_history();
CREATE TRIGGER authorizations_history BEFORE UPDATE OR DELETE ON administrator_recovery.authorizations
    FOR EACH ROW EXECUTE FUNCTION administrator_recovery.guard_history();
CREATE TRIGGER authorizations_no_truncate BEFORE TRUNCATE ON administrator_recovery.authorizations
    FOR EACH STATEMENT EXECUTE FUNCTION administrator_recovery.guard_history();
CREATE TRIGGER reset_evidence_append_only BEFORE UPDATE OR DELETE OR TRUNCATE
    ON administrator_recovery.reset_evidence FOR EACH STATEMENT
    EXECUTE FUNCTION administrator_lifecycle.reject_evidence_mutation();

CREATE FUNCTION administrator_recovery.configure_operator(
    p_operator NAME, p_runtime NAME, p_migration NAME, p_bootstrap NAME, p_database TEXT)
RETURNS VOID LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog AS $$
DECLARE setting TEXT;
BEGIN
    PERFORM administrator_recovery.require_owner();
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Recovery requires READ COMMITTED' USING ERRCODE = '25001';
    END IF;
    PERFORM pg_advisory_xact_lock(1094995278, 1);
    IF p_database IS DISTINCT FROM current_database() OR p_operator IS NULL
        OR p_runtime IS NULL OR p_migration IS NULL OR p_bootstrap IS NULL
        OR p_operator IN (p_runtime, p_migration, p_bootstrap, session_user)
        OR p_runtime = p_migration
        OR p_migration <> pg_get_userbyid((SELECT nspowner FROM pg_namespace WHERE nspname = 'administrator_recovery'))
        OR EXISTS (SELECT 1 FROM administrator_lifecycle.operator_identity WHERE operator_login = p_operator)
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = p_operator AND rolcanlogin AND NOT rolinherit
            AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls)
        OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = p_operator))
        OR EXISTS (SELECT 1 FROM pg_shdepend WHERE refclassid = 'pg_authid'::regclass
            AND refobjid = (SELECT oid FROM pg_roles WHERE rolname = p_operator) AND deptype = 'o')
        OR EXISTS (SELECT 1 FROM administrator_recovery.operator_identity WHERE active AND operator_login <> p_operator) THEN
        RAISE EXCEPTION 'Reset identity separation is invalid' USING ERRCODE = '42501';
    END IF;
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public, administrator_lifecycle, administrator_recovery FROM %I', p_operator);
    EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public, administrator_lifecycle, administrator_recovery FROM %I', p_operator);
    EXECUTE format('REVOKE ALL ON SCHEMA public, administrator_lifecycle FROM %I', p_operator);
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), p_operator);
    EXECUTE format('GRANT USAGE ON SCHEMA administrator_recovery TO %I', p_operator);
    EXECUTE format('GRANT EXECUTE ON FUNCTION administrator_recovery.reset(UUID,UUID,BIGINT,UUID,BYTEA,TEXT,TEXT,TEXT) TO %I', p_operator);
    EXECUTE format('GRANT SELECT ON administrator_recovery.reset_evidence TO %I', p_operator);
    FOR setting IN SELECT unnest(ARRAY['log_statement=none', 'log_min_duration_statement=-1',
        'log_min_duration_sample=-1', 'log_transaction_sample_rate=0', 'log_parameter_max_length=0',
        'log_parameter_max_length_on_error=0', 'log_error_verbosity=terse']) LOOP
        EXECUTE format('ALTER ROLE %I IN DATABASE %I SET %I = %L', p_operator, current_database(),
            split_part(setting, '=', 1), split_part(setting, '=', 2));
    END LOOP;
    INSERT INTO administrator_recovery.operator_identity
        (operator_login, runtime_login, migration_login, bootstrap_login, database_name, environment_name)
        VALUES (p_operator, p_runtime, p_migration, p_bootstrap, current_database(), 'local')
        ON CONFLICT (singleton) DO UPDATE SET operator_login = EXCLUDED.operator_login,
            runtime_login = EXCLUDED.runtime_login, migration_login = EXCLUDED.migration_login,
            bootstrap_login = EXCLUDED.bootstrap_login, database_name = EXCLUDED.database_name, active = TRUE;
END;
$$;

-- Only the trusted enrollment tool can obtain a password hash; it never prints or persists it.
CREATE FUNCTION administrator_recovery.enrollment_target(p_target UUID, p_database TEXT, p_environment TEXT)
RETURNS TABLE(password_hash TEXT, credential_version BIGINT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE target public.users%ROWTYPE;
BEGIN
    PERFORM administrator_recovery.require_owner();
    PERFORM administrator_recovery.require_sensitive_session();
    target := administrator_recovery.lock_target(p_target, p_database, p_environment);
    RETURN QUERY SELECT target.password::TEXT, target.credential_version;
END;
$$;

CREATE FUNCTION administrator_recovery.enroll(
    p_id UUID, p_target UUID, p_version BIGINT, p_verifier BYTEA, p_database TEXT, p_environment TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE target public.users%ROWTYPE;
BEGIN
    PERFORM administrator_recovery.require_owner();
    PERFORM administrator_recovery.require_sensitive_session();
    target := administrator_recovery.lock_target(p_target, p_database, p_environment);
    IF p_id IS NULL OR p_verifier IS NULL OR octet_length(p_verifier) <> 32
        OR p_version IS DISTINCT FROM target.credential_version
        OR EXISTS (SELECT 1 FROM administrator_recovery.emergency_secrets
            WHERE target_user_id = p_target AND consumed_at IS NULL AND revoked_at IS NULL) THEN
        RAISE EXCEPTION 'Enrollment rejected' USING ERRCODE = '22023';
    END IF;
    INSERT INTO administrator_recovery.emergency_secrets
        (id, target_user_id, database_name, environment_name, verifier, enrolled_by, enrolled_credential_version, created_at)
        VALUES (p_id, p_target, current_database(), 'local', p_verifier, session_user, p_version, clock_timestamp());
    RETURN p_id;
END;
$$;

CREATE FUNCTION administrator_recovery.authorize(
    p_target UUID, p_version BIGINT, p_secret_id UUID, p_secret BYTEA, p_database TEXT, p_environment TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE target public.users%ROWTYPE; authorization UUID; observed TIMESTAMPTZ;
BEGIN
    PERFORM administrator_recovery.require_owner();
    PERFORM administrator_recovery.require_sensitive_session();
    target := administrator_recovery.lock_target(p_target, p_database, p_environment);
    IF p_version IS DISTINCT FROM target.credential_version THEN
        RAISE EXCEPTION 'Recovery version is stale' USING ERRCODE = '22023';
    END IF;
    PERFORM administrator_recovery.verify_secret(p_secret_id, p_target, p_database, p_secret);
    UPDATE administrator_recovery.authorizations SET revoked_at = clock_timestamp()
        WHERE target_user_id = p_target AND consumed_at IS NULL AND revoked_at IS NULL;
    observed := clock_timestamp();
    INSERT INTO administrator_recovery.authorizations
        (secret_id, target_user_id, database_name, environment_name, operator_login, authorized_by,
            expected_credential_version, created_at, expires_at)
        SELECT p_secret_id, p_target, current_database(), 'local', operator_login, session_user,
            p_version, observed, observed + INTERVAL '10 minutes'
        FROM administrator_recovery.operator_identity RETURNING id INTO authorization;
    RETURN authorization;
END;
$$;

CREATE FUNCTION administrator_recovery.require_operator()
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    IF system_user IS NULL OR system_user NOT IN ('scram-sha-256:' || session_user, 'md5:' || session_user)
        OR NOT EXISTS (SELECT 1 FROM administrator_recovery.operator_identity
        WHERE active AND operator_login = session_user AND database_name = current_database())
        OR EXISTS (SELECT 1 FROM administrator_lifecycle.operator_identity WHERE operator_login = session_user)
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = session_user AND rolcanlogin AND NOT rolinherit
            AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls)
        OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = session_user))
        OR EXISTS (SELECT 1 FROM pg_shdepend WHERE refclassid = 'pg_authid'::regclass
            AND refobjid = (SELECT oid FROM pg_roles WHERE rolname = session_user) AND deptype = 'o') THEN
        RAISE EXCEPTION 'Reset operator rejected' USING ERRCODE = '42501';
    END IF;
END;
$$;

CREATE FUNCTION administrator_recovery.reset(
    p_authorization UUID, p_target UUID, p_version BIGINT, p_secret_id UUID, p_secret BYTEA,
    p_password_hash TEXT, p_database TEXT, p_environment TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE target public.users%ROWTYPE; authority administrator_recovery.authorizations%ROWTYPE;
    observed TIMESTAMPTZ; receipt UUID;
BEGIN
    PERFORM administrator_recovery.require_operator();
    PERFORM administrator_recovery.require_sensitive_session();
    target := administrator_recovery.lock_target(p_target, p_database, p_environment);
    PERFORM administrator_recovery.require_operator();
    SELECT * INTO authority FROM administrator_recovery.authorizations WHERE id = p_authorization FOR UPDATE;
    IF authority.id IS NULL OR authority.target_user_id IS DISTINCT FROM p_target
        OR authority.secret_id IS DISTINCT FROM p_secret_id OR authority.operator_login IS DISTINCT FROM session_user
        OR authority.database_name IS DISTINCT FROM current_database() OR authority.environment_name <> 'local'
        OR authority.expected_credential_version IS DISTINCT FROM p_version
        OR target.credential_version IS DISTINCT FROM p_version
        OR authority.consumed_at IS NOT NULL OR authority.revoked_at IS NOT NULL
        OR p_password_hash IS NULL OR p_password_hash !~ '^\$2a\$10\$[./A-Za-z0-9]{21}[.Oeu][./A-Za-z0-9]{30}[.CGKOSWaeimquy26]$' THEN
        RAISE EXCEPTION 'Reset authorization rejected' USING ERRCODE = '22023';
    END IF;
    PERFORM administrator_recovery.verify_secret(p_secret_id, p_target, p_database, p_secret);
    observed := clock_timestamp();
    IF authority.expires_at <= observed THEN
        RAISE EXCEPTION 'Reset authorization expired' USING ERRCODE = '22023';
    END IF;
    UPDATE public.users SET password = p_password_hash, credential_version = credential_version + 1,
        updated_at = observed AT TIME ZONE 'UTC' WHERE id = p_target;
    UPDATE public.password_recovery_challenges SET revoked_at = observed
        WHERE user_id = p_target AND consumed_at IS NULL AND revoked_at IS NULL;
    observed := clock_timestamp();
    UPDATE administrator_recovery.authorizations SET consumed_at = observed
        WHERE id = p_authorization AND expires_at > observed;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Reset authorization expired' USING ERRCODE = '22023';
    END IF;
    UPDATE administrator_recovery.emergency_secrets SET consumed_at = observed WHERE id = p_secret_id;
    INSERT INTO administrator_recovery.reset_evidence
        (authorization_id, target_user_id, operator_login, database_name, operation,
            credential_version_before, credential_version_after)
        VALUES (p_authorization, p_target, session_user, current_database(), 'RESET_SOLE_ADMIN',
            p_version, p_version + 1) RETURNING id INTO receipt;
    RETURN receipt;
END;
$$;

CREATE FUNCTION administrator_recovery.revoke_secret(p_secret_id UUID, p_target UUID, p_database TEXT, p_environment TEXT)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    PERFORM administrator_recovery.require_owner();
    PERFORM administrator_recovery.lock_target(p_target, p_database, p_environment);
    IF NOT EXISTS (SELECT 1 FROM administrator_recovery.emergency_secrets WHERE id = p_secret_id AND target_user_id = p_target) THEN
        RAISE EXCEPTION 'Recovery secret not found' USING ERRCODE = '22023';
    END IF;
    UPDATE administrator_recovery.authorizations SET revoked_at = clock_timestamp()
        WHERE secret_id = p_secret_id AND consumed_at IS NULL AND revoked_at IS NULL;
    UPDATE administrator_recovery.emergency_secrets SET revoked_at = clock_timestamp()
        WHERE id = p_secret_id AND consumed_at IS NULL AND revoked_at IS NULL;
END;
$$;

CREATE FUNCTION administrator_recovery.revoke_operator()
RETURNS VOID LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog AS $$
DECLARE operator NAME;
BEGIN
    PERFORM administrator_recovery.require_owner();
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Recovery requires READ COMMITTED' USING ERRCODE = '25001';
    END IF;
    PERFORM pg_advisory_xact_lock(1094995278, 1);
    SELECT operator_login INTO operator FROM administrator_recovery.operator_identity;
    UPDATE administrator_recovery.authorizations SET revoked_at = clock_timestamp()
        WHERE consumed_at IS NULL AND revoked_at IS NULL;
    UPDATE administrator_recovery.operator_identity SET active = FALSE;
    IF operator IS NOT NULL THEN
        EXECUTE format('ALTER ROLE %I NOLOGIN', operator);
        EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA administrator_recovery FROM %I', operator);
        EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA administrator_recovery FROM %I', operator);
        EXECUTE format('REVOKE ALL ON SCHEMA administrator_recovery FROM %I', operator);
    END IF;
END;
$$;

-- Execute after revocation commits: NOLOGIN then prevents a new-session race during termination.
CREATE FUNCTION administrator_recovery.terminate_operator_sessions()
RETURNS VOID LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog AS $$
DECLARE operator NAME; enabled BOOLEAN;
BEGIN
    PERFORM administrator_recovery.require_owner();
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Recovery requires READ COMMITTED' USING ERRCODE = '25001';
    END IF;
    PERFORM pg_advisory_xact_lock(1094995278, 1);
    SELECT operator_login, active INTO operator, enabled FROM administrator_recovery.operator_identity;
    IF operator IS NULL THEN RETURN; END IF;
    IF enabled OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = operator AND rolcanlogin) THEN
        RAISE EXCEPTION 'Commit operator revocation before terminating sessions' USING ERRCODE = '55000';
    END IF;
    PERFORM pg_terminate_backend(pid, 5000) FROM pg_stat_activity WHERE usename = operator AND pid <> pg_backend_pid();
    PERFORM pg_stat_clear_snapshot();
    IF EXISTS (SELECT 1 FROM pg_stat_activity WHERE usename = operator) THEN
        RAISE EXCEPTION 'Operator session termination is not confirmed' USING ERRCODE = '55000';
    END IF;
END;
$$;

CREATE FUNCTION administrator_recovery.invalidate_restored_authority()
RETURNS VOID LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog AS $$
BEGIN
    PERFORM administrator_recovery.revoke_operator();
    UPDATE administrator_recovery.emergency_secrets SET revoked_at = clock_timestamp()
        WHERE consumed_at IS NULL AND revoked_at IS NULL;
END;
$$;

REVOKE ALL ON ALL TABLES IN SCHEMA administrator_recovery FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA administrator_recovery FROM PUBLIC;
