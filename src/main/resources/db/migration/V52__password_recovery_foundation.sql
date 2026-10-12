CREATE TABLE password_recovery_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    format_version SMALLINT NOT NULL CHECK (format_version = 1),
    verifier BYTEA NOT NULL CHECK (octet_length(verifier) = 32),
    issued_credential_version BIGINT NOT NULL CHECK (issued_credential_version >= 0),
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at)),
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at = created_at + INTERVAL '15 minutes'),
    CHECK (consumed_at IS NULL OR (isfinite(consumed_at) AND consumed_at >= created_at)),
    CHECK (revoked_at IS NULL OR (isfinite(revoked_at) AND revoked_at >= created_at)),
    CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);

CREATE UNIQUE INDEX ux_password_recovery_one_open_user
    ON password_recovery_challenges(user_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

-- Reuse V51's domain before any challenge row lock, including direct SQL writes.
CREATE TRIGGER password_recovery_serialize BEFORE INSERT OR UPDATE OR DELETE
    ON password_recovery_challenges FOR EACH STATEMENT
    EXECUTE FUNCTION administrator_lifecycle.serialize_transition();

CREATE FUNCTION guard_password_recovery_transition()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    observed TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.id, NEW.user_id, NEW.format_version, NEW.verifier,
               NEW.issued_credential_version, NEW.created_at, NEW.expires_at)
            IS DISTINCT FROM ROW(OLD.id, OLD.user_id, OLD.format_version, OLD.verifier,
               OLD.issued_credential_version, OLD.created_at, OLD.expires_at) THEN
            RAISE EXCEPTION 'Recovery issuance data is immutable' USING ERRCODE = '23514';
        END IF;
        IF OLD.consumed_at IS NOT NULL OR OLD.revoked_at IS NOT NULL THEN
            IF ROW(NEW.consumed_at, NEW.revoked_at) IS DISTINCT FROM ROW(OLD.consumed_at, OLD.revoked_at) THEN
                RAISE EXCEPTION 'Recovery terminal state is irreversible' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END IF;
        IF NEW.consumed_at IS NULL THEN RETURN NEW; END IF;
        observed := clock_timestamp();
        IF OLD.expires_at <= observed THEN
            RAISE EXCEPTION 'Recovery challenge is not usable' USING ERRCODE = '23514';
        END IF;
        NEW.consumed_at := observed;
    ELSE
        IF NEW.consumed_at IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
            RAISE EXCEPTION 'Recovery challenge must start open' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = NEW.user_id
        AND NOT u.deleted AND u.enabled AND u.credential_version = NEW.issued_credential_version
        AND NOT EXISTS (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
            WHERE ur.user_id = u.id AND r.name = 'ROLE_ADMIN')) THEN
        RAISE EXCEPTION 'Recovery account is not eligible' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER password_recovery_guard BEFORE INSERT OR UPDATE
    ON password_recovery_challenges FOR EACH ROW
    EXECUTE FUNCTION guard_password_recovery_transition();

CREATE FUNCTION reject_runtime_recovery_history_removal()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF current_user <> pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid = TG_RELID)) THEN
        RAISE EXCEPTION 'Recovery history removal requires the database owner' USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER password_recovery_no_runtime_delete BEFORE DELETE
    ON password_recovery_challenges FOR EACH ROW
    EXECUTE FUNCTION reject_runtime_recovery_history_removal();
CREATE TRIGGER password_recovery_no_runtime_truncate BEFORE TRUNCATE
    ON password_recovery_challenges FOR EACH STATEMENT
    EXECUTE FUNCTION reject_runtime_recovery_history_removal();

REVOKE ALL ON password_recovery_challenges FROM PUBLIC;
REVOKE ALL ON FUNCTION guard_password_recovery_transition() FROM PUBLIC;
REVOKE ALL ON FUNCTION reject_runtime_recovery_history_removal() FROM PUBLIC;
