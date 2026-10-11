-- One lock order: administrator domain, User lifecycle advisory lock, User row.
-- The two-int key space is separate from existing bigint User/commerce keys.
CREATE SCHEMA administrator_lifecycle;
REVOKE ALL ON SCHEMA administrator_lifecycle FROM PUBLIC;

CREATE TABLE administrator_lifecycle.operator_identity (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    operator_login NAME NOT NULL,
    database_name NAME NOT NULL,
    environment_name TEXT NOT NULL CHECK (environment_name = 'local')
);

CREATE TABLE administrator_lifecycle.promotion_evidence (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    target_user_id UUID NOT NULL,
    operator_login NAME NOT NULL,
    database_name NAME NOT NULL,
    operation TEXT NOT NULL CHECK (operation = 'ZERO_ADMIN_PROMOTION'),
    credential_version_before BIGINT NOT NULL CHECK (credential_version_before >= 0),
    credential_version_after BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    CHECK (credential_version_after > credential_version_before)
);

CREATE FUNCTION administrator_lifecycle.serialize_transition()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Administrator lifecycle requires READ COMMITTED' USING ERRCODE = '25001';
    END IF;
    PERFORM pg_advisory_xact_lock(1094995278, 1);
    RETURN NULL;
END;
$$;

CREATE FUNCTION administrator_lifecycle.reject_untrusted_role_mutation()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF current_user <> pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid = TG_RELID)) THEN
        RAISE EXCEPTION 'Role mutation requires a trusted database operation' USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER roles_serialize BEFORE INSERT OR UPDATE OR DELETE ON public.roles
    FOR EACH STATEMENT EXECUTE FUNCTION administrator_lifecycle.serialize_transition();
CREATE TRIGGER roles_guard BEFORE INSERT OR UPDATE OR DELETE ON public.roles
    FOR EACH ROW EXECUTE FUNCTION administrator_lifecycle.reject_untrusted_role_mutation();
CREATE TRIGGER user_roles_serialize BEFORE INSERT OR UPDATE OR DELETE ON public.user_roles
    FOR EACH STATEMENT EXECUTE FUNCTION administrator_lifecycle.serialize_transition();
CREATE TRIGGER user_roles_guard BEFORE INSERT OR UPDATE OR DELETE ON public.user_roles
    FOR EACH ROW EXECUTE FUNCTION administrator_lifecycle.reject_untrusted_role_mutation();

CREATE FUNCTION administrator_lifecycle.protect_user_transition()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    administrator BOOLEAN;
    other_usable BOOLEAN;
BEGIN
    SELECT EXISTS (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
        WHERE ur.user_id = OLD.id AND r.name = 'ROLE_ADMIN') INTO administrator;
    SELECT EXISTS (SELECT 1 FROM public.users u JOIN public.user_roles ur ON ur.user_id = u.id
        JOIN public.roles r ON r.id = ur.role_id
        WHERE u.id <> OLD.id AND NOT u.deleted AND u.enabled AND r.name = 'ROLE_ADMIN') INTO other_usable;
    IF administrator AND NOT OLD.deleted AND OLD.enabled
        AND (TG_OP = 'DELETE' OR NEW.deleted OR NOT NEW.enabled) AND NOT other_usable THEN
        RAISE EXCEPTION 'Administrator continuity must be preserved' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF NEW.id <> OLD.id OR NEW.email <> OLD.email OR (OLD.deleted AND NOT NEW.deleted)
        OR NEW.credential_version < OLD.credential_version THEN
        RAISE EXCEPTION 'User identity and credential lifecycle must be preserved' USING ERRCODE = '23514';
    END IF;
    IF OLD.enabled AND NOT NEW.enabled AND NEW.credential_version <= OLD.credential_version THEN
        RAISE EXCEPTION 'Disablement must advance credential version' USING ERRCODE = '23514';
    END IF;
    IF NEW.password IS DISTINCT FROM OLD.password AND NEW.credential_version <= OLD.credential_version THEN
        RAISE EXCEPTION 'Password change must advance credential version' USING ERRCODE = '23514';
    END IF;
    IF administrator AND NOT OLD.enabled AND NEW.enabled AND NOT NEW.deleted AND NOT other_usable THEN
        RAISE EXCEPTION 'Zero administrator recovery requires the offline operator' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_serialize BEFORE UPDATE OR DELETE ON public.users
    FOR EACH STATEMENT EXECUTE FUNCTION administrator_lifecycle.serialize_transition();
CREATE TRIGGER users_lifecycle_guard BEFORE UPDATE OR DELETE ON public.users
    FOR EACH ROW EXECUTE FUNCTION administrator_lifecycle.protect_user_transition();

-- This creates a new identity; no existing UUID or assignable role is accepted.
CREATE FUNCTION public.register_role_user(p_email TEXT, p_password TEXT, p_first_name TEXT, p_last_name TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    registered UUID;
    user_role UUID;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Registration requires READ COMMITTED' USING ERRCODE = '25001';
    END IF;
    PERFORM pg_advisory_xact_lock(1094995278, 1);
    SELECT id INTO STRICT user_role FROM public.roles WHERE name = 'ROLE_USER';
    INSERT INTO public.users (email, password, first_name, last_name)
        VALUES (p_email, p_password, p_first_name, p_last_name) RETURNING id INTO registered;
    INSERT INTO public.user_roles (user_id, role_id) VALUES (registered, user_role);
    RETURN registered;
END;
$$;
REVOKE ALL ON FUNCTION public.register_role_user(TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;

CREATE FUNCTION administrator_lifecycle.promote_zero_administrator(
    p_target UUID, p_expected_database TEXT, p_expected_environment TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    operator administrator_lifecycle.operator_identity%ROWTYPE;
    target public.users%ROWTYPE;
    admin_role UUID;
    evidence UUID;
BEGIN
    SELECT * INTO operator FROM administrator_lifecycle.operator_identity WHERE singleton;
    IF operator.operator_login IS NULL OR session_user <> operator.operator_login
        OR current_database() <> operator.database_name OR p_expected_database IS DISTINCT FROM current_database()
        OR p_expected_environment IS DISTINCT FROM operator.environment_name
        OR operator.environment_name <> 'local'
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = session_user AND rolcanlogin
            AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls)
        OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = session_user)) THEN
        RAISE EXCEPTION 'Local operator authority or environment is invalid' USING ERRCODE = '42501';
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Administrator lifecycle requires READ COMMITTED' USING ERRCODE = '25001';
    END IF;
    PERFORM pg_advisory_xact_lock(1094995278, 1);
    PERFORM pg_advisory_xact_lock(hashtextextended(p_target::TEXT, 0));
    IF EXISTS (SELECT 1 FROM public.users u JOIN public.user_roles ur ON ur.user_id = u.id
        JOIN public.roles r ON r.id = ur.role_id WHERE NOT u.deleted AND u.enabled AND r.name = 'ROLE_ADMIN') THEN
        RAISE EXCEPTION 'A usable administrator already exists' USING ERRCODE = '55000';
    END IF;
    SELECT * INTO target FROM public.users WHERE id = p_target FOR UPDATE;
    IF target.id IS NULL OR target.deleted OR NOT target.enabled THEN
        RAISE EXCEPTION 'Target is not eligible' USING ERRCODE = '22023';
    END IF;
    SELECT id INTO STRICT admin_role FROM public.roles WHERE name = 'ROLE_ADMIN';
    INSERT INTO public.user_roles (user_id, role_id) VALUES (p_target, admin_role);
    UPDATE public.users SET credential_version = credential_version + 1,
        updated_at = clock_timestamp() AT TIME ZONE 'UTC' WHERE id = p_target;
    INSERT INTO administrator_lifecycle.promotion_evidence
        (target_user_id, operator_login, database_name, operation, credential_version_before, credential_version_after)
        VALUES (p_target, session_user, current_database(), 'ZERO_ADMIN_PROMOTION',
            target.credential_version, target.credential_version + 1) RETURNING id INTO evidence;
    RETURN evidence;
END;
$$;

CREATE FUNCTION administrator_lifecycle.reject_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'Promotion evidence is append-only' USING ERRCODE = '42501';
END;
$$;
CREATE TRIGGER promotion_evidence_append_only BEFORE UPDATE OR DELETE OR TRUNCATE
    ON administrator_lifecycle.promotion_evidence FOR EACH STATEMENT
    EXECUTE FUNCTION administrator_lifecycle.reject_evidence_mutation();

REVOKE ALL ON ALL TABLES IN SCHEMA administrator_lifecycle FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA administrator_lifecycle FROM PUBLIC;

-- Preserve existing runtime registration grants while removing direct role DML.
DO $$
DECLARE recipient NAME;
BEGIN
    FOR recipient IN SELECT DISTINCT pg_get_userbyid(a.grantee)
        FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a
        WHERE c.oid IN ('public.roles'::regclass, 'public.user_roles'::regclass)
            AND a.grantee <> 0 AND a.grantee <> c.relowner
            AND a.privilege_type IN ('INSERT', 'UPDATE', 'DELETE')
    LOOP
        EXECUTE format('REVOKE INSERT, UPDATE, DELETE ON public.roles, public.user_roles FROM %I', recipient);
        EXECUTE format('GRANT EXECUTE ON FUNCTION public.register_role_user(TEXT,TEXT,TEXT,TEXT) TO %I', recipient);
    END LOOP;
    REVOKE INSERT, UPDATE, DELETE ON public.roles, public.user_roles FROM PUBLIC;
END;
$$;
