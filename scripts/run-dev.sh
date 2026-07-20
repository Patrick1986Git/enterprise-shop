#!/bin/sh
set -eu

usage() {
  cat <<'USAGE'
Usage: ./scripts/run-dev.sh [--prepare-only]

Starts local PostgreSQL, verifies or repairs the least-privilege application
runtime role, and starts Spring Boot on the host. Use --prepare-only to prepare
only PostgreSQL and the runtime role without starting Maven/Spring Boot.
USAGE
}

log() {
  printf '%s\n' "==> $*"
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

script_path=$0
case "$script_path" in
  */*) ;;
  *)
    script_path=$(command -v -- "$script_path") || fail "Cannot resolve script path: $0"
    ;;
esac

script_dir=$(CDPATH='' cd -- "$(dirname -- "$script_path")" && pwd -P) || fail "Cannot resolve script directory"
repo_root=$(CDPATH='' cd -- "$script_dir/.." && pwd -P) || fail "Cannot resolve repository root"
cd "$repo_root" || fail "Cannot enter repository root: $repo_root"

prepare_only=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --prepare-only)
      prepare_only=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      fail "Unknown argument: $1"
      ;;
  esac
  shift
done

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    fail "Required command not found: $1"
  fi
}

is_env_set() {
  env | grep -q "^$1="
}

assign_env_if_unset() {
  key=$1
  value=$2
  if ! is_env_set "$key"; then
    export "$key=$value"
  fi
}

load_env_file() {
  env_file=$1
  [ -f "$env_file" ] || return 0

  log "Loading local environment defaults from $env_file"
  line_number=0
  carriage_return=$(printf '\r')
  tab=$(printf '\t')
  while IFS= read -r line || [ -n "$line" ]; do
    line_number=$((line_number + 1))
    line=${line%"$carriage_return"}
    while :; do
      case "$line" in
        ' '*) line=${line#' '} ;;
        "$tab"*) line=${line#"$tab"} ;;
        *) break ;;
      esac
    done

    while :; do
      case "$line" in
        *' ') line=${line%' '} ;;
        *"$tab") line=${line%"$tab"} ;;
        *) break ;;
      esac
    done

    case "$line" in
      ''|'#'*) continue ;;
      export\ *) line=${line#export } ;;
    esac

    case "$line" in
      *=*) ;;
      *) fail "$env_file:$line_number uses unsupported dotenv syntax; expected KEY=VALUE" ;;
    esac

    key=${line%%=*}
    value=${line#*=}

    case "$key" in
      ''|*[!A-Za-z0-9_]*|[0-9]*)
        fail "$env_file:$line_number contains invalid variable name: $key"
        ;;
    esac

    if [ "${value#*\`}" != "$value" ] || [ "${value#*\$\(}" != "$value" ]; then
      fail "$env_file:$line_number contains command substitution, which is not allowed"
    fi

    case "$value" in
      \"*\")
        value=${value#\"}
        value=${value%\"}
        ;;
      \"*)
        fail "$env_file:$line_number has an unterminated double-quoted value"
        ;;
      \'*\')
        value=${value#\'}
        value=${value%\'}
        ;;
      \'*)
        fail "$env_file:$line_number has an unterminated single-quoted value"
        ;;
    esac

    assign_env_if_unset "$key" "$value"
  done < "$env_file"
}

validate_identifier() {
  name=$1
  value=$2
  case "$value" in
    ''|*[!A-Za-z0-9_]*)
      fail "$name must contain only letters, numbers, and underscores and cannot be empty"
      ;;
  esac
}

validate_runtime_identity() {
  validate_identifier APP_DB_USER "$APP_DB_USER"
  validate_identifier POSTGRES_USER "$POSTGRES_USER"

  if [ "$APP_DB_USER" = "$POSTGRES_USER" ]; then
    fail "APP_DB_USER must differ from POSTGRES_USER; the host application must not run as the PostgreSQL admin user"
  fi

  if [ "${DATABASE_USERNAME+x}" ]; then
    if [ "$DATABASE_USERNAME" != "$APP_DB_USER" ]; then
      fail "DATABASE_USERNAME conflicts with APP_DB_USER; run-dev.sh always starts the host application as APP_DB_USER"
    fi
  fi

  if [ "${DATABASE_PASSWORD+x}" ]; then
    if [ "$DATABASE_PASSWORD" != "$APP_DB_PASSWORD" ]; then
      fail "DATABASE_PASSWORD conflicts with APP_DB_PASSWORD; run-dev.sh always starts the host application with APP_DB_PASSWORD"
    fi
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
WITH role_attributes AS (
  SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication
  FROM pg_roles
  WHERE rolname = current_user
), table_privileges AS (
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
  EXISTS (
    SELECT 1
    FROM role_attributes
    WHERE rolcanlogin
      AND NOT rolsuper
      AND NOT rolcreatedb
      AND NOT rolcreaterole
      AND NOT rolreplication
  )
  AND has_database_privilege(current_user, current_database(), 'CONNECT')
  AND has_schema_privilege(current_user, 'public', 'USAGE')
  AND COALESCE((SELECT ok FROM table_privileges), true)
  AND COALESCE((SELECT ok FROM sequence_privileges), true)
  AND COALESCE((SELECT ok FROM function_privileges), true)
THEN 'ok' ELSE 'invalid_runtime_role' END;
SQL
)

check_app_role() {
  result=$(psql_as_app --tuples-only --no-align --command "$app_role_check_sql" 2>/dev/null || true)
  [ "$result" = "ok" ]
}

load_env_file "$repo_root/.env"

: "${POSTGRES_DB:=enterprise_shop_dev}"
: "${POSTGRES_USER:=postgres}"
: "${POSTGRES_PASSWORD:=postgres}"
: "${POSTGRES_HOST_PORT:=5433}"
: "${APP_DB_USER:=shop_dev}"
: "${APP_DB_PASSWORD:=shop_dev}"

validate_runtime_identity

export POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD POSTGRES_HOST_PORT APP_DB_USER APP_DB_PASSWORD
export DATABASE_URL="${DATABASE_URL:-jdbc:postgresql://localhost:${POSTGRES_HOST_PORT}/${POSTGRES_DB}}"
export FLYWAY_URL="${FLYWAY_URL:-$DATABASE_URL}"
export FLYWAY_USER="${FLYWAY_USER:-$POSTGRES_USER}"
export FLYWAY_PASSWORD="${FLYWAY_PASSWORD:-$POSTGRES_PASSWORD}"
export DATABASE_USERNAME="$APP_DB_USER"
export DATABASE_PASSWORD="$APP_DB_PASSWORD"

require_command docker

log "Starting PostgreSQL with Docker Compose from $repo_root"
docker compose up -d --wait postgres

log "Checking whether application role $APP_DB_USER can authenticate, has least-privilege attributes, and has required grants"
if check_app_role; then
  log "Application role $APP_DB_USER is ready; bootstrap is not needed"
else
  log "Application role $APP_DB_USER is missing, cannot authenticate, has unsafe attributes, or lacks required privileges"
  log "Running one-shot database-role-bootstrap without deleting or recreating the PostgreSQL volume"
  docker compose run --rm --no-deps --build database-role-bootstrap

  log "Rechecking application role $APP_DB_USER"
  if ! check_app_role; then
    fail "Application role $APP_DB_USER still cannot authenticate, has unsafe attributes, or lacks required privileges after bootstrap"
  fi
fi

if [ "$prepare_only" = true ]; then
  log "Local database is prepared; --prepare-only requested, so Spring Boot will not be started"
  exit 0
fi

log "Starting Enterprise Shop on the host with Spring profile dev"
log "Datasource user: $DATABASE_USERNAME; Flyway user: $FLYWAY_USER; database URL: $DATABASE_URL"
exec env SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
