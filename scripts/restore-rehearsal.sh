#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_CONTAINER="enterprise-shop-restore-source"
readonly TARGET_CONTAINER="enterprise-shop-restore-target"
readonly SOURCE_APP="enterprise-shop-restore-source-app"
readonly TARGET_APP="enterprise-shop-restore-target-app"
readonly NETWORK="enterprise-shop-restore-rehearsal"
readonly POSTGRES_IMAGE="enterprise-shop/postgres:restore-rehearsal"
readonly APP_IMAGE="enterprise-shop/app:restore-rehearsal"
readonly DATABASE="restore_rehearsal"
readonly ADMIN_USER="restore_admin"
readonly MIGRATION_USER="restore_migration"
readonly RUNTIME_USER="restore_runtime"
readonly ADMIN_PASSWORD="synthetic-admin-password"
readonly MIGRATION_PASSWORD="synthetic-migration-password"
readonly RUNTIME_PASSWORD="synthetic-runtime-password"
readonly DUMP_FILE="${TMPDIR:-/tmp}/enterprise-shop-restore-rehearsal-${$}.dump"

export POSTGRES_USER="$ADMIN_USER" POSTGRES_PASSWORD="$ADMIN_PASSWORD" POSTGRES_DB=postgres
export DATABASE_USERNAME="$RUNTIME_USER" DATABASE_PASSWORD="$RUNTIME_PASSWORD"
export FLYWAY_USER="$MIGRATION_USER" FLYWAY_PASSWORD="$MIGRATION_PASSWORD"

# shellcheck source=scripts/lib/postgres-readiness.sh
source scripts/lib/postgres-readiness.sh

cleanup() {
  docker rm -f "$SOURCE_APP" "$TARGET_APP" "$SOURCE_CONTAINER" "$TARGET_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  rm -f "$DUMP_FILE"
}
trap cleanup EXIT INT TERM

fail() { printf 'restore rehearsal: %s\n' "$*" >&2; exit 1; }
for command in docker curl sha256sum stat date; do
  command -v "$command" >/dev/null 2>&1 || fail "required command is unavailable: $command"
done
docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable"
[[ -r scripts/restore-rehearsal-fixture.sql ]] || fail "run from the repository root"
[[ ! -e "$DUMP_FILE" ]] || fail "refusing to overwrite $DUMP_FILE"

cleanup
docker network create "$NETWORK" >/dev/null
docker build --tag "$POSTGRES_IMAGE" docker/postgres >/dev/null
docker build --tag "$APP_IMAGE" . >/dev/null

start_database() {
  local container=$1
  docker run -d --name "$container" --network "$NETWORK" \
    --env POSTGRES_USER --env POSTGRES_PASSWORD --env POSTGRES_DB "$POSTGRES_IMAGE" >/dev/null
  wait_for_final_postgres "$container" "$ADMIN_USER" \
    || fail "final PostgreSQL server did not become ready in $container"
  docker exec -i "$container" psql --username "$ADMIN_USER" --dbname postgres \
    --set=ON_ERROR_STOP=1 --set=migration_user="$MIGRATION_USER" \
    --set=migration_password="$MIGRATION_PASSWORD" --set=runtime_user="$RUNTIME_USER" \
    --set=runtime_password="$RUNTIME_PASSWORD" --set=database="$DATABASE" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT PASSWORD %L',
              :'migration_user', :'migration_password') \gexec
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT PASSWORD %L',
              :'runtime_user', :'runtime_password') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'database', :'migration_user') \gexec
SQL
  docker exec -i "$container" psql --username "$ADMIN_USER" --dbname "$DATABASE" \
    --set=ON_ERROR_STOP=1 --set=migration_user="$MIGRATION_USER" --set=runtime_user="$RUNTIME_USER" <<'SQL'
SELECT format('ALTER SCHEMA public OWNER TO %I', :'migration_user') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'runtime_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'runtime_user') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'migration_user', :'runtime_user') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', :'migration_user', :'runtime_user') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO %I', :'migration_user', :'runtime_user') \gexec
SQL
}

app_environment() {
  local host=$1
  export SPRING_PROFILES_ACTIVE=prod
  export DATABASE_URL="jdbc:postgresql://${host}:5432/${DATABASE}"
  export FLYWAY_URL="$DATABASE_URL"
  export DATABASE_MAXIMUM_POOL_SIZE=4 DATABASE_MINIMUM_IDLE=0 DATABASE_CONNECTION_TIMEOUT_MILLISECONDS=30000
  export SERVER_TOMCAT_THREADS_MAX=20 SERVER_TOMCAT_MAX_CONNECTIONS=100 SERVER_TOMCAT_ACCEPT_COUNT=20
  export SERVER_TOMCAT_CONNECTION_TIMEOUT=20s JWT_KEY_ID=restore-rehearsal
  export JWT_SECRET=c3ludGhldGljLXJlc3RvcmUtcmVoZWFyc2FsLWtleS0zMi1ieXRlcyE=
  export STRIPE_SECRET_KEY=sk_test_synthetic_restore_only
  export STRIPE_WEBHOOK_SECRET=whsec_synthetic_restore_only STRIPE_PUBLIC_KEY=pk_test_synthetic_restore_only
  export STRIPE_CONNECT_TIMEOUT=PT1S STRIPE_READ_TIMEOUT=PT1S STRIPE_MAX_NETWORK_RETRIES=0
}

start_app() {
  local name=$1 host=$2
  local -a args=()
  app_environment "$host"
  for variable in SPRING_PROFILES_ACTIVE DATABASE_URL DATABASE_USERNAME DATABASE_PASSWORD FLYWAY_URL FLYWAY_USER \
    FLYWAY_PASSWORD DATABASE_MAXIMUM_POOL_SIZE DATABASE_MINIMUM_IDLE DATABASE_CONNECTION_TIMEOUT_MILLISECONDS \
    SERVER_TOMCAT_THREADS_MAX SERVER_TOMCAT_MAX_CONNECTIONS SERVER_TOMCAT_ACCEPT_COUNT \
    SERVER_TOMCAT_CONNECTION_TIMEOUT JWT_KEY_ID JWT_SECRET STRIPE_SECRET_KEY STRIPE_WEBHOOK_SECRET \
    STRIPE_PUBLIC_KEY STRIPE_CONNECT_TIMEOUT STRIPE_READ_TIMEOUT STRIPE_MAX_NETWORK_RETRIES; do
    args+=(--env "$variable")
  done
  docker run -d --name "$name" --network "$NETWORK" --read-only --tmpfs /tmp:rw,noexec,nosuid,size=128m \
    --cap-drop ALL --security-opt no-new-privileges:true "${args[@]}" "$APP_IMAGE" >/dev/null
  for _ in {1..90}; do
    if docker exec "$name" curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1; then return 0; fi
    if [[ "$(docker inspect --format '{{.State.Running}}' "$name")" != true ]]; then
      docker logs "$name" >&2; fail "application exited before readiness: $name"
    fi
    sleep 1
  done
  docker logs "$name" >&2; fail "application readiness timed out: $name"
}

grant_current_objects() {
  local container=$1
  docker exec -i "$container" psql --username "$ADMIN_USER" --dbname "$DATABASE" \
    --set=ON_ERROR_STOP=1 --set=runtime_user="$RUNTIME_USER" <<'SQL'
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', :'runtime_user') \gexec
SELECT format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO %I', :'runtime_user') \gexec
SELECT format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO %I', :'runtime_user') \gexec
SQL
}

start_database "$SOURCE_CONTAINER"
source_version=$(docker exec "$SOURCE_CONTAINER" postgres --version)
start_app "$SOURCE_APP" "$SOURCE_CONTAINER"
grant_current_objects "$SOURCE_CONTAINER"
export PGPASSWORD="$RUNTIME_PASSWORD"
docker exec --env PGPASSWORD -i "$SOURCE_CONTAINER" psql --host 127.0.0.1 \
  --username "$RUNTIME_USER" --dbname "$DATABASE" < scripts/restore-rehearsal-fixture.sql

backup_started=$(date +%s)
docker exec "$SOURCE_CONTAINER" pg_dump --username "$ADMIN_USER" --dbname "$DATABASE" \
  --format=custom --file=/tmp/rehearsal.dump
docker cp "$SOURCE_CONTAINER:/tmp/rehearsal.dump" "$DUMP_FILE" >/dev/null
backup_seconds=$(( $(date +%s) - backup_started ))
docker run --rm -v "$DUMP_FILE:/rehearsal.dump:ro" "$POSTGRES_IMAGE" pg_restore --list /rehearsal.dump >/dev/null
dump_digest=$(sha256sum "$DUMP_FILE" | awk '{print $1}')
dump_size=$(stat -c %s "$DUMP_FILE")

start_database "$TARGET_CONTAINER"
target_version=$(docker exec "$TARGET_CONTAINER" postgres --version)
docker cp "$DUMP_FILE" "$TARGET_CONTAINER:/tmp/rehearsal.dump" >/dev/null
restore_started=$(date +%s)
docker exec "$TARGET_CONTAINER" pg_restore --username "$ADMIN_USER" --dbname "$DATABASE" \
  --exit-on-error --single-transaction /tmp/rehearsal.dump
restore_seconds=$(( $(date +%s) - restore_started ))

verify_sql=$(cat <<'SQL'
DO $$
DECLARE invalid_count integer;
BEGIN
  SELECT count(*) INTO invalid_count FROM flyway_schema_history WHERE NOT success;
  IF invalid_count <> 0 OR (SELECT max(version::integer) FROM flyway_schema_history) <> 50 THEN
    RAISE EXCEPTION 'Flyway history is not complete through V50';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_extension WHERE extname = 'uuid-ossp')
     OR NOT EXISTS (SELECT FROM pg_extension WHERE extname = 'unaccent') THEN
    RAISE EXCEPTION 'required extensions are missing';
  END IF;
  IF to_tsvector('public.polish', 'żółty produkt') @@ to_tsquery('public.polish', 'żółty') IS NOT TRUE THEN
    RAISE EXCEPTION 'Polish text search is not operational';
  END IF;
  IF (SELECT count(*) FROM pg_constraint WHERE conname IN
      ('fk_order_items_order', 'uq_payments_order_id', 'chk_outbox_events_event_version_positive')) <> 3
     OR (SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname IN
      ('idx_products_search_vector', 'idx_outbox_events_status_next_attempt_at',
       'idx_notifications_claim_recovery')) <> 3 THEN
    RAISE EXCEPTION 'expected constraints/indexes are incomplete';
  END IF;
  IF (SELECT count(*) FROM pg_trigger WHERE tgname IN
      ('notification_admin_action_logs_append_only', 'outbox_event_admin_action_logs_append_only',
       'reservation_expiration_admin_action_logs_append_only') AND NOT tgisinternal) <> 3
     OR NOT EXISTS (SELECT FROM pg_proc WHERE proname = 'reject_runtime_admin_action_log_mutation') THEN
    RAISE EXCEPTION 'V45 append-only trigger/function contract is incomplete';
  END IF;
  IF (SELECT count(*) FROM users WHERE id='10000000-0000-0000-0000-000000000001' AND credential_version=7) <> 1
     OR (SELECT count(*) FROM products WHERE id='30000000-0000-0000-0000-000000000001' AND stock=37 AND version=11) <> 1
     OR (SELECT count(*) FROM order_items WHERE id='51000000-0000-0000-0000-000000000001' AND product_name='Immutable synthetic product snapshot' AND product_sku='SYNTHETIC-SNAPSHOT-SKU') <> 1
     OR (SELECT count(*) FROM payments WHERE provider_payment_id='pi_synthetic_restore_only') <> 1
     OR (SELECT count(*) FROM stripe_webhook_events WHERE stripe_event_id='evt_synthetic_restore_only') <> 1
     OR (SELECT count(*) FROM reservation_expiration_work WHERE id='70000000-0000-0000-0000-000000000001' AND attempts=3 AND recovery_count=1) <> 1
     OR (SELECT count(*) FROM outbox_events WHERE id='80000000-0000-0000-0000-000000000001' AND attempts=4 AND event_version=5) <> 1
     OR (SELECT count(*) FROM notifications WHERE id='90000000-0000-0000-0000-000000000001' AND attempts=6 AND requeue_count=2) <> 1 THEN
    RAISE EXCEPTION 'restored synthetic data invariant failed';
  END IF;
  IF (SELECT count(*) FROM notification_admin_action_logs WHERE id='a0000000-0000-0000-0000-000000000001') <> 1
     OR (SELECT count(*) FROM outbox_event_admin_action_logs WHERE id='a1000000-0000-0000-0000-000000000001') <> 1
     OR (SELECT count(*) FROM reservation_expiration_admin_action_logs WHERE id='a2000000-0000-0000-0000-000000000001') <> 1 THEN
    RAISE EXCEPTION 'restored ADMIN history invariant failed';
  END IF;
END $$;
SQL
)
docker exec -i "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" \
  --set=ON_ERROR_STOP=1 <<<"$verify_sql"

owner_result=$(docker exec "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align -c \
  "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p','S','v','m','f') AND pg_get_userbyid(c.relowner) <> '$MIGRATION_USER';")
[[ "$owner_result" == 0 ]] || fail "migration identity does not own every application relation"
runtime_owned=$(docker exec "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align -c \
  "SELECT count(*) FROM pg_class WHERE relowner=(SELECT oid FROM pg_roles WHERE rolname='$RUNTIME_USER');")
[[ "$runtime_owned" == 0 ]] || fail "runtime identity owns database objects"
role_result=$(docker exec "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname postgres --tuples-only --no-align -c \
  "SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolinherit AND NOT EXISTS (SELECT FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname='$RUNTIME_USER';")
[[ "$role_result" == t ]] || fail "runtime role attributes are unsafe"
default_acl=$(docker exec "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align -c \
  "SELECT count(*) FROM pg_default_acl d CROSS JOIN LATERAL aclexplode(d.defaclacl) a WHERE d.defaclrole=(SELECT oid FROM pg_roles WHERE rolname='$MIGRATION_USER') AND a.grantee=(SELECT oid FROM pg_roles WHERE rolname='$RUNTIME_USER');")
[[ "$default_acl" -ge 3 ]] || fail "migration-owner default privileges are incomplete"

for table in notification_admin_action_logs outbox_event_admin_action_logs reservation_expiration_admin_action_logs; do
  denial=$(docker exec --env PGPASSWORD "$TARGET_CONTAINER" psql --host 127.0.0.1 \
    --username "$RUNTIME_USER" --dbname "$DATABASE" --set=VERBOSITY=verbose --command \
    "UPDATE $table SET actor_email='forbidden@example.invalid';" 2>&1 || true)
  grep -Eq 'ERROR:[[:space:]]+42501:' <<<"$denial" || fail "runtime UPDATE was not denied with 42501 on $table"
  denial=$(docker exec --env PGPASSWORD "$TARGET_CONTAINER" psql --host 127.0.0.1 \
    --username "$RUNTIME_USER" --dbname "$DATABASE" --set=VERBOSITY=verbose --command "DELETE FROM $table;" 2>&1 || true)
  grep -Eq 'ERROR:[[:space:]]+42501:' <<<"$denial" || fail "runtime DELETE was not denied with 42501 on $table"
done

docker exec --env PGPASSWORD "$TARGET_CONTAINER" psql --host 127.0.0.1 \
  --username "$RUNTIME_USER" --dbname "$DATABASE" --set=ON_ERROR_STOP=1 --command \
  "INSERT INTO categories(name,slug) VALUES ('Runtime restore DML','runtime-restore-dml'); DELETE FROM categories WHERE slug='runtime-restore-dml';" >/dev/null
docker exec "$TARGET_CONTAINER" psql --username "$MIGRATION_USER" --dbname "$DATABASE" --set=ON_ERROR_STOP=1 \
  --command "COMMENT ON TABLE categories IS 'migration maintenance verified';" >/dev/null

# UUID defaults replace numeric application sequences; generation after restore proves a restored identifier cannot collide.
docker exec --env PGPASSWORD "$TARGET_CONTAINER" psql --host 127.0.0.1 \
  --username "$RUNTIME_USER" --dbname "$DATABASE" --set=ON_ERROR_STOP=1 --command \
  "INSERT INTO categories(name,slug) VALUES ('UUID collision check','uuid-collision-check');" >/dev/null

start_app "$TARGET_APP" "$TARGET_CONTAINER"
commit_sha=$(git rev-parse HEAD 2>/dev/null || printf unknown)
printf '%s\n' \
  "restore_rehearsal=passed" "commit_sha=$commit_sha" "source_postgresql=$source_version" \
  "target_postgresql=$target_version" "dump_sha256=$dump_digest" "dump_bytes=$dump_size" \
  "backup_seconds=$backup_seconds" "restore_seconds=$restore_seconds" \
  "flyway_validation=passed" "ownership_privileges=passed" "data_invariants=passed" \
  "application_readiness=passed" "cleanup=armed"
