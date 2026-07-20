#!/bin/sh
set -eu

: "${POSTGRES_DB:=enterprise_shop_dev}"
: "${POSTGRES_USER:=postgres}"
: "${POSTGRES_HOST:=postgres}"
: "${POSTGRES_PORT:=5432}"
: "${APP_DB_USER:=shop_dev}"
: "${APP_DB_PASSWORD:=shop_dev}"

case "$APP_DB_USER" in
  *[!A-Za-z0-9_]*|'')
    echo "APP_DB_USER must contain only letters, numbers, and underscores" >&2
    exit 1
    ;;
esac

echo "Bootstrapping PostgreSQL runtime role ${APP_DB_USER} on ${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}"

psql --set=ON_ERROR_STOP=1 \
  --host "$POSTGRES_HOST" \
  --port "$POSTGRES_PORT" \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=postgres_db="$POSTGRES_DB" \
  --set=postgres_user="$POSTGRES_USER" \
  --set=app_db_user="$APP_DB_USER" \
  --set=app_db_password="$APP_DB_PASSWORD" <<'SQL'
SELECT format(
  'CREATE ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',
  :'app_db_user',
  :'app_db_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_db_user')\gexec

SELECT format(
  'ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',
  :'app_db_user',
  :'app_db_password'
)\gexec

GRANT CONNECT ON DATABASE :"postgres_db" TO :"app_db_user";
GRANT USAGE ON SCHEMA public TO :"app_db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"app_db_user";
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO :"app_db_user";
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO :"app_db_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"postgres_user" IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_db_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"postgres_user" IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :"app_db_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"postgres_user" IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO :"app_db_user";
SQL
