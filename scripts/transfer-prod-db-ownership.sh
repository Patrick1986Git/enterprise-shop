#!/bin/sh
set -eu

: "${DATABASE_ADMIN_URL:?DATABASE_ADMIN_URL is required}"
: "${DATABASE_NAME:?DATABASE_NAME is required}"
: "${DATABASE_ADMIN_USER:?DATABASE_ADMIN_USER is required}"
: "${LEGACY_RUNTIME_USER:?LEGACY_RUNTIME_USER is required}"
: "${FLYWAY_USER:?FLYWAY_USER is required}"
: "${APP_SCHEMA:=public}"

validate_identifier() {
  name=$1
  value=$2
  case "$value" in
    *[!A-Za-z0-9_]*|'')
      echo "$name must contain only letters, numbers, and underscores" >&2
      exit 1
      ;;
  esac
}

validate_identifier DATABASE_NAME "$DATABASE_NAME"
validate_identifier DATABASE_ADMIN_USER "$DATABASE_ADMIN_USER"
validate_identifier LEGACY_RUNTIME_USER "$LEGACY_RUNTIME_USER"
validate_identifier FLYWAY_USER "$FLYWAY_USER"
validate_identifier APP_SCHEMA "$APP_SCHEMA"

if [ "$LEGACY_RUNTIME_USER" = "$FLYWAY_USER" ]; then
  echo "LEGACY_RUNTIME_USER and FLYWAY_USER must differ" >&2
  exit 1
fi

echo "Transferring application object ownership in ${DATABASE_NAME}/${APP_SCHEMA} to the migration identity"

psql "$DATABASE_ADMIN_URL" \
  --username "$DATABASE_ADMIN_USER" \
  --set=ON_ERROR_STOP=1 \
  --set=expected_database="$DATABASE_NAME" \
  --set=app_schema="$APP_SCHEMA" \
  --set=legacy_runtime="$LEGACY_RUNTIME_USER" \
  --set=flyway_user="$FLYWAY_USER" <<'SQL'
SELECT set_config('shop.expected_database', :'expected_database', false);
SELECT set_config('shop.app_schema', :'app_schema', false);
SELECT set_config('shop.legacy_runtime', :'legacy_runtime', false);
SELECT set_config('shop.flyway_user', :'flyway_user', false);

DO $$
DECLARE
    legacy_oid OID;
    admin_is_superuser BOOLEAN;
BEGIN
    IF current_database() <> current_setting('shop.expected_database') THEN
        RAISE EXCEPTION 'Connected database does not match DATABASE_NAME';
    END IF;

    SELECT rolsuper INTO admin_is_superuser FROM pg_roles WHERE rolname = current_user;
    IF NOT coalesce(admin_is_superuser, false) THEN
        RAISE EXCEPTION 'Ownership transfer requires a PostgreSQL superuser administrative identity';
    END IF;

    SELECT oid INTO legacy_oid FROM pg_roles WHERE rolname = current_setting('shop.legacy_runtime');
    IF legacy_oid IS NULL OR NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = current_setting('shop.flyway_user')
    ) THEN
        RAISE EXCEPTION 'Legacy runtime and migration roles must already exist';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_database WHERE datdba = legacy_oid AND datname <> current_database())
       OR EXISTS (SELECT 1 FROM pg_tablespace WHERE spcowner = legacy_oid)
       OR EXISTS (
           SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE c.relowner = legacy_oid AND n.nspname <> current_setting('shop.app_schema')
       )
       OR EXISTS (
           SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
           WHERE p.proowner = legacy_oid AND n.nspname <> current_setting('shop.app_schema')
       )
       OR EXISTS (
           SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
           WHERE t.typowner = legacy_oid AND n.nspname <> current_setting('shop.app_schema')
       )
       OR EXISTS (
           SELECT 1 FROM pg_namespace WHERE nspowner = legacy_oid
             AND nspname <> current_setting('shop.app_schema')
       )
       OR EXISTS (
           SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
           WHERE e.extowner = legacy_oid AND n.nspname <> current_setting('shop.app_schema')
       )
       OR EXISTS (
           SELECT 1 FROM pg_ts_config c JOIN pg_namespace n ON n.oid = c.cfgnamespace
           WHERE c.cfgowner = legacy_oid AND n.nspname <> current_setting('shop.app_schema')
       )
       OR EXISTS (
           SELECT 1 FROM pg_ts_dict d JOIN pg_namespace n ON n.oid = d.dictnamespace
           WHERE d.dictowner = legacy_oid AND n.nspname <> current_setting('shop.app_schema')
       ) THEN
        RAISE EXCEPTION 'Legacy runtime owns objects outside the intended database/schema boundary';
    END IF;
END
$$;

SELECT format('REASSIGN OWNED BY %I TO %I', :'legacy_runtime', :'flyway_user') \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'expected_database', :'legacy_runtime') \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', :'app_schema', :'legacy_runtime') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', :'app_schema', :'legacy_runtime') \gexec
SELECT format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA %I TO %I', :'app_schema', :'legacy_runtime') \gexec
SELECT format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA %I TO %I', :'app_schema', :'legacy_runtime') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'flyway_user', :'app_schema', :'legacy_runtime') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', :'flyway_user', :'app_schema', :'legacy_runtime') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT EXECUTE ON FUNCTIONS TO %I', :'flyway_user', :'app_schema', :'legacy_runtime') \gexec

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_roles r ON r.oid = c.relowner
        WHERE n.nspname = current_setting('shop.app_schema')
          AND r.rolname = current_setting('shop.legacy_runtime')
    ) THEN
        RAISE EXCEPTION 'Runtime ownership remains after transfer';
    END IF;
END
$$;
SQL

echo "Ownership transfer and runtime grants completed"
