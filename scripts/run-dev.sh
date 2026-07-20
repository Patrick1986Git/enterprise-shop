#!/bin/sh
set -eu

log() {
  printf '%s\n' "==> $*"
}

warn() {
  printf '%s\n' "WARN: $*" >&2
}

load_env_file() {
  env_file=$1
  [ -f "$env_file" ] || return 0

  log "Loading local environment defaults from $env_file"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      ''|'#'*) continue ;;
      export\ *) line=${line#export } ;;
    esac

    case "$line" in
      *=*) ;;
      *) continue ;;
    esac

    key=${line%%=*}
    value=${line#*=}

    case "$key" in
      ''|*[!A-Za-z0-9_]*)
        warn "Ignoring invalid .env key: $key"
        continue
        ;;
      [0-9]*)
        warn "Ignoring invalid .env key: $key"
        continue
        ;;
    esac

    case "$value" in
      \"*\")
        value=${value#\"}
        value=${value%\"}
        ;;
      \'*\')
        value=${value#\'}
        value=${value%\'}
        ;;
    esac

    if [ "${value#*\`}" != "$value" ] || [ "${value#*\$\(}" != "$value" ]; then
      warn "Ignoring $key because command substitution syntax is not allowed in .env"
      continue
    fi

    eval "current=\${$key+x}"
    if [ -z "$current" ]; then
      export "$key=$value"
    fi
  done < "$env_file"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'Required command not found: %s\n' "$1" >&2
    exit 1
  fi
}

psql_as_app() {
  docker compose exec -T \
    -e "PGPASSWORD=$APP_DB_PASSWORD" \
    postgres psql \
      --host 127.0.0.1 \
      --port 5432 \
      --username "$APP_DB_USER" \
      --dbname "$POSTGRES_DB" \
      --set=ON_ERROR_STOP=1 \
      "$@"
}

app_role_check_sql=$(cat <<'SQL'
WITH table_privileges AS (
  SELECT bool_and(
    has_table_privilege(current_user, format('%I.%I', schemaname, tablename), 'SELECT,INSERT,UPDATE,DELETE')
  ) AS ok
  FROM pg_tables
  WHERE schemaname = 'public'
), sequence_privileges AS (
  SELECT bool_and(
    has_sequence_privilege(current_user, format('%I.%I', sequence_schema, sequence_name), 'USAGE,SELECT,UPDATE')
  ) AS ok
  FROM information_schema.sequences
  WHERE sequence_schema = 'public'
), function_privileges AS (
  SELECT bool_and(has_function_privilege(current_user, p.oid, 'EXECUTE')) AS ok
  FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
  WHERE n.nspname = 'public'
)
SELECT CASE WHEN
  has_database_privilege(current_user, current_database(), 'CONNECT')
  AND has_schema_privilege(current_user, 'public', 'USAGE')
  AND COALESCE((SELECT ok FROM table_privileges), true)
  AND COALESCE((SELECT ok FROM sequence_privileges), true)
  AND COALESCE((SELECT ok FROM function_privileges), true)
THEN 'ok' ELSE 'missing_required_privileges' END;
SQL
)

check_app_role() {
  result=$(psql_as_app --tuples-only --no-align --command "$app_role_check_sql" 2>/dev/null || true)
  [ "$result" = "ok" ]
}

load_env_file .env

: "${POSTGRES_DB:=enterprise_shop_dev}"
: "${POSTGRES_USER:=postgres}"
: "${POSTGRES_PASSWORD:=postgres}"
: "${POSTGRES_HOST_PORT:=5433}"
: "${APP_DB_USER:=shop_dev}"
: "${APP_DB_PASSWORD:=shop_dev}"

export POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD POSTGRES_HOST_PORT APP_DB_USER APP_DB_PASSWORD
export DATABASE_URL="${DATABASE_URL:-jdbc:postgresql://localhost:${POSTGRES_HOST_PORT}/${POSTGRES_DB}}"
export DATABASE_USERNAME="${DATABASE_USERNAME:-$APP_DB_USER}"
export DATABASE_PASSWORD="${DATABASE_PASSWORD:-$APP_DB_PASSWORD}"
export FLYWAY_URL="${FLYWAY_URL:-$DATABASE_URL}"
export FLYWAY_USER="${FLYWAY_USER:-$POSTGRES_USER}"
export FLYWAY_PASSWORD="${FLYWAY_PASSWORD:-$POSTGRES_PASSWORD}"

require_command docker

log "Starting PostgreSQL with Docker Compose"
docker compose up -d --wait postgres

log "Checking whether application role $APP_DB_USER can authenticate and has required privileges"
if check_app_role; then
  log "Application role $APP_DB_USER is ready; bootstrap is not needed"
else
  log "Application role $APP_DB_USER is missing, cannot authenticate, or lacks required privileges"
  log "Running one-shot database-role-bootstrap without deleting or recreating the PostgreSQL volume"
  docker compose run --rm --no-deps --build database-role-bootstrap

  log "Rechecking application role $APP_DB_USER"
  if ! check_app_role; then
    printf 'Application role %s still cannot authenticate or lacks required privileges after bootstrap.\n' "$APP_DB_USER" >&2
    exit 1
  fi
fi

log "Starting Enterprise Shop on the host with Spring profile dev"
log "Datasource user: $DATABASE_USERNAME; Flyway user: $FLYWAY_USER; database URL: $DATABASE_URL"
exec env SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
