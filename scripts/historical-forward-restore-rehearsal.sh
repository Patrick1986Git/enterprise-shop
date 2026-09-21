#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_CONTAINER="enterprise-shop-historical-forward-source"
readonly TARGET_CONTAINER="enterprise-shop-historical-forward-target"
readonly SOURCE_APP="enterprise-shop-historical-forward-source-app"
readonly TARGET_APP="enterprise-shop-historical-forward-target-app"
readonly NETWORK="enterprise-shop-historical-forward-rehearsal"
readonly POSTGRES_IMAGE="enterprise-shop/postgres:historical-forward-rehearsal"
readonly APP_IMAGE="enterprise-shop/app:historical-forward-rehearsal"
readonly DATABASE="historical_forward_rehearsal"
readonly ADMIN_USER="restore_admin"
readonly MIGRATION_USER="restore_migration"
readonly RUNTIME_USER="restore_runtime"
readonly ADMIN_PASSWORD="synthetic-admin-password"
readonly MIGRATION_PASSWORD="synthetic-migration-password"
readonly RUNTIME_PASSWORD="synthetic-runtime-password"
readonly DUMP_FILE="${TMPDIR:-/tmp}/enterprise-shop-historical-forward-rehearsal-${$}.dump"

export POSTGRES_USER="$ADMIN_USER" POSTGRES_PASSWORD="$ADMIN_PASSWORD" POSTGRES_DB=postgres
export DATABASE_USERNAME="$RUNTIME_USER" DATABASE_PASSWORD="$RUNTIME_PASSWORD"
export FLYWAY_USER="$MIGRATION_USER" FLYWAY_PASSWORD="$MIGRATION_PASSWORD"

cleanup() {
  docker rm -f "$SOURCE_APP" "$TARGET_APP" "$SOURCE_CONTAINER" "$TARGET_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  rm -f "$DUMP_FILE"
}
trap cleanup EXIT INT TERM

fail() { printf 'historical forward restore rehearsal: %s\n' "$*" >&2; exit 1; }
for command in docker curl sha256sum stat date timeout; do
  command -v "$command" >/dev/null 2>&1 || fail "required command is unavailable: $command"
done
docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable"
[[ -r scripts/restore-rehearsal-fixture.sql ]] || fail "run from the repository root"
[[ ! -e "$DUMP_FILE" ]] || fail "refusing to overwrite $DUMP_FILE"

cleanup
docker network create "$NETWORK" >/dev/null
docker build --tag "$POSTGRES_IMAGE" docker/postgres >/dev/null
docker build --tag "$APP_IMAGE" . >/dev/null

wait_for_postgres() {
  local container=$1
  for _ in {1..60}; do
    if docker exec "$container" pg_isready --username "$ADMIN_USER" --dbname postgres >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  fail "PostgreSQL did not become ready in $container"
}

start_database() {
  local container=$1
  docker run -d --name "$container" --network "$NETWORK" \
    --env POSTGRES_USER --env POSTGRES_PASSWORD --env POSTGRES_DB "$POSTGRES_IMAGE" >/dev/null
  wait_for_postgres "$container"
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
  export SERVER_TOMCAT_CONNECTION_TIMEOUT=20s JWT_KEY_ID=historical-forward-rehearsal
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
  for variable in SPRING_FLYWAY_TARGET SPRING_JPA_HIBERNATE_DDL_AUTO; do
    [[ -v "$variable" ]] && args+=(--env "$variable")
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

readonly HISTORICAL_SCENARIO="product-review-and-credential-version-upgrade"
readonly HISTORICAL_VERSION=48

migration_versions() {
  find src/main/resources/db/migration -maxdepth 1 -type f -printf '%f\n' \
    | sed -n 's/^V\([0-9][0-9]*\)__.*/\1/p' | sort -n
}

mapfile -t ALL_VERSIONS < <(migration_versions)
CURRENT_VERSION=${ALL_VERSIONS[-1]}
[[ "$CURRENT_VERSION" -ge 50 ]] || fail "scenario requires current migrations V49 and V50"
[[ -f src/main/resources/db/migration/V49__snapshot_product_review_author_name.sql ]] \
  || fail "scenario contract migration V49 is absent"
[[ -f src/main/resources/db/migration/V50__add_user_credential_version.sql ]] \
  || fail "scenario contract migration V50 is absent"
mapfile -t EXPECTED_PENDING < <(printf '%s\n' "${ALL_VERSIONS[@]}" | awk -v checkpoint="$HISTORICAL_VERSION" '$1 > checkpoint')
[[ " ${EXPECTED_PENDING[*]} " == *" 49 "* && " ${EXPECTED_PENDING[*]} " == *" 50 "* ]] \
  || fail "historical checkpoint no longer exercises both V49 and V50"
[[ -r scripts/historical-forward-restore-fixture.sql ]] || fail "run from the repository root"

start_database "$SOURCE_CONTAINER"
source_version=$(docker exec "$SOURCE_CONTAINER" postgres --version)

# Use the current application's bundled Flyway with an explicit immutable target. Hibernate validation
# is disabled only for this source-construction process because the current entity model is intentionally
# newer than V48; the restored target later starts with normal production validation.
export SPRING_FLYWAY_TARGET="$HISTORICAL_VERSION" SPRING_JPA_HIBERNATE_DDL_AUTO=none
start_app "$SOURCE_APP" "$SOURCE_CONTAINER"
docker rm -f "$SOURCE_APP" >/dev/null
unset SPRING_FLYWAY_TARGET SPRING_JPA_HIBERNATE_DDL_AUTO

grant_current_objects "$SOURCE_CONTAINER"
export PGPASSWORD="$RUNTIME_PASSWORD"
docker exec --env PGPASSWORD -i "$SOURCE_CONTAINER" psql --host 127.0.0.1 \
  --username "$RUNTIME_USER" --dbname "$DATABASE" < scripts/historical-forward-restore-fixture.sql

history_fingerprint() {
  local container=$1 upper=${2:-999999}
  docker exec "$container" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align \
    --command "SELECT md5(string_agg(version || ':' || checksum, ',' ORDER BY installed_rank)) FROM flyway_schema_history WHERE version IS NOT NULL AND version::integer <= $upper;"
}

source_history=$(docker exec "$SOURCE_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align \
  --command "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version IS NOT NULL;")
source_latest=$(docker exec "$SOURCE_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align \
  --command "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")
[[ "$source_latest" == "$HISTORICAL_VERSION" ]] || fail "historical source did not stop at V$HISTORICAL_VERSION"
source_failed=$(docker exec "$SOURCE_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align \
  --command "SELECT count(*) FROM flyway_schema_history WHERE NOT success;")
[[ "$source_failed" == 0 ]] || fail "historical source contains failed Flyway history"
source_checksum=$(history_fingerprint "$SOURCE_CONTAINER" "$HISTORICAL_VERSION")
for column in author_name credential_version; do
  present=$(docker exec "$SOURCE_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align \
    --command "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='$column';")
  [[ "$present" == 0 ]] || fail "post-checkpoint column $column leaked into historical source"
done

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

# Prove the fresh target is still genuinely historical before current startup.
target_pre_latest=$(docker exec "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align \
  --command "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")
[[ "$target_pre_latest" == "$HISTORICAL_VERSION" ]] || fail "fresh target is not at historical V$HISTORICAL_VERSION"
[[ "$(history_fingerprint "$TARGET_CONTAINER" "$HISTORICAL_VERSION")" == "$source_checksum" ]] \
  || fail "historical checksums changed during restore"
pre_rows=$(docker exec "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align \
  --command "SELECT count(*) FROM product_reviews WHERE id IN ('32000000-0000-0000-0000-000000000001','32000000-0000-0000-0000-000000000002');")
[[ "$pre_rows" == 2 ]] || fail "historical review rows did not survive restore"
for column in author_name credential_version; do
  present=$(docker exec "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align \
    --command "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='$column';")
  [[ "$present" == 0 ]] || fail "post-checkpoint column $column exists before current startup"
done
pre_owner_count=$(docker exec "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align -c \
  "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p','S','v','m','f') AND pg_get_userbyid(c.relowner) <> '$MIGRATION_USER';")
[[ "$pre_owner_count" == 0 ]] || fail "historical restore weakened migration ownership"
pre_runtime_owned=$(docker exec "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align -c \
  "SELECT count(*) FROM pg_class WHERE relowner=(SELECT oid FROM pg_roles WHERE rolname='$RUNTIME_USER');")
[[ "$pre_runtime_owned" == 0 ]] || fail "runtime owns objects before migration"

start_app "$TARGET_APP" "$TARGET_CONTAINER"
applied_versions=$(docker exec "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --tuples-only --no-align \
  --command "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version::integer > $HISTORICAL_VERSION;")
expected_versions=$(IFS=,; echo "${EXPECTED_PENDING[*]}")
[[ "$applied_versions" == "$expected_versions" ]] \
  || fail "pending migrations differ: expected $expected_versions, applied $applied_versions"

verify_sql=$(cat <<SQL
DO \$\$
BEGIN
  IF EXISTS (SELECT FROM flyway_schema_history WHERE NOT success)
     OR (SELECT max(version::integer) FROM flyway_schema_history) <> $CURRENT_VERSION THEN
    RAISE EXCEPTION 'Flyway history is not complete through current V$CURRENT_VERSION';
  END IF;
  IF (SELECT author_name FROM product_reviews WHERE id='32000000-0000-0000-0000-000000000001') <> 'Alex Morgan'
     OR (SELECT author_name FROM product_reviews WHERE id='32000000-0000-0000-0000-000000000002') <> 'Anonymous' THEN
    RAISE EXCEPTION 'V49 author snapshot transformation failed';
  END IF;
  IF (SELECT count(*) FROM users WHERE id IN
      ('10000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000002')
      AND credential_version=0) <> 2 THEN
    RAISE EXCEPTION 'V50 credential default failed';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_extension WHERE extname='uuid-ossp')
     OR NOT EXISTS (SELECT FROM pg_extension WHERE extname='unaccent')
     OR to_tsvector('public.polish', 'żółty produkt') @@ to_tsquery('public.polish', 'żółty') IS NOT TRUE THEN
    RAISE EXCEPTION 'extension or Polish search invariant failed';
  END IF;
  IF (SELECT count(*) FROM pg_constraint WHERE conname IN
      ('fk_order_items_order','uq_payments_order_id','chk_outbox_events_event_version_positive')) <> 3
     OR (SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname IN
      ('idx_products_search_vector','idx_outbox_events_status_next_attempt_at',
       'idx_notifications_claim_recovery')) <> 3 THEN
    RAISE EXCEPTION 'restored constraints or indexes are incomplete';
  END IF;
  IF (SELECT count(*) FROM products WHERE id='30000000-0000-0000-0000-000000000001' AND stock=37 AND version=11) <> 1
     OR (SELECT count(*) FROM order_items WHERE id='51000000-0000-0000-0000-000000000001' AND product_name='Immutable synthetic product snapshot') <> 1
     OR (SELECT count(*) FROM payments WHERE provider_payment_id='pi_synthetic_restore_only') <> 1
     OR (SELECT count(*) FROM stripe_webhook_events WHERE stripe_event_id='evt_synthetic_restore_only') <> 1
     OR (SELECT count(*) FROM reservation_expiration_work WHERE id='70000000-0000-0000-0000-000000000001' AND attempts=3 AND recovery_count=1) <> 1
     OR (SELECT count(*) FROM outbox_events WHERE id='80000000-0000-0000-0000-000000000001' AND event_version=5) <> 1
     OR (SELECT count(*) FROM notifications WHERE id='90000000-0000-0000-0000-000000000001' AND attempts=6 AND requeue_count=2) <> 1
     OR (SELECT count(*) FROM notification_admin_action_logs WHERE id='a0000000-0000-0000-0000-000000000001') <> 1
     OR (SELECT count(*) FROM outbox_event_admin_action_logs WHERE id='a1000000-0000-0000-0000-000000000001') <> 1
     OR (SELECT count(*) FROM reservation_expiration_admin_action_logs WHERE id='a2000000-0000-0000-0000-000000000001') <> 1 THEN
    RAISE EXCEPTION 'pre-existing business data invariant failed';
  END IF;
  IF (SELECT count(*) FROM pg_trigger WHERE tgname IN
      ('notification_admin_action_logs_append_only','outbox_event_admin_action_logs_append_only',
       'reservation_expiration_admin_action_logs_append_only') AND NOT tgisinternal) <> 3 THEN
    RAISE EXCEPTION 'V45 append-only triggers are incomplete';
  END IF;
END \$\$;
SQL
)
docker exec -i "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" \
  --set=ON_ERROR_STOP=1 <<<"$verify_sql"
[[ "$(history_fingerprint "$TARGET_CONTAINER" "$HISTORICAL_VERSION")" == "$source_checksum" ]] \
  || fail "historical Flyway checksums changed during forward migration"

for statement in \
  "UPDATE product_reviews SET author_name=NULL WHERE id='32000000-0000-0000-0000-000000000001'" \
  "UPDATE product_reviews SET author_name='  ' WHERE id='32000000-0000-0000-0000-000000000001'" \
  "UPDATE users SET credential_version=-1 WHERE id='10000000-0000-0000-0000-000000000001'"; do
  if docker exec "$TARGET_CONTAINER" psql --username "$MIGRATION_USER" --dbname "$DATABASE" \
      --set=ON_ERROR_STOP=1 --command "$statement" >/dev/null 2>&1; then
    fail "post-migration constraint accepted forbidden data"
  fi
done

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
  grep -Eq 'ERROR:[[:space:]]+42501:' <<<"$denial" || fail "V45 runtime UPDATE was not denied on $table"
  denial=$(docker exec --env PGPASSWORD "$TARGET_CONTAINER" psql --host 127.0.0.1 \
    --username "$RUNTIME_USER" --dbname "$DATABASE" --set=VERBOSITY=verbose --command "DELETE FROM $table;" 2>&1 || true)
  grep -Eq 'ERROR:[[:space:]]+42501:' <<<"$denial" || fail "V45 runtime DELETE was not denied on $table"
done

docker exec --env PGPASSWORD "$TARGET_CONTAINER" psql --host 127.0.0.1 \
  --username "$RUNTIME_USER" --dbname "$DATABASE" --set=ON_ERROR_STOP=1 --command \
  "INSERT INTO categories(name,slug) VALUES ('Historical runtime DML','historical-runtime-dml'); DELETE FROM categories WHERE slug='historical-runtime-dml';" >/dev/null
docker exec "$TARGET_CONTAINER" psql --username "$MIGRATION_USER" --dbname "$DATABASE" --set=ON_ERROR_STOP=1 \
  --command "COMMENT ON TABLE categories IS 'historical forward migration maintenance verified';" >/dev/null
docker exec --env PGPASSWORD "$TARGET_CONTAINER" psql --host 127.0.0.1 \
  --username "$RUNTIME_USER" --dbname "$DATABASE" --set=ON_ERROR_STOP=1 --command \
  "INSERT INTO categories(name,slug) VALUES ('Historical UUID check','historical-uuid-check');" >/dev/null

# A separate final startup proves checksum corruption fails closed; repair/baseline are never used.
docker rm -f "$TARGET_APP" >/dev/null
docker exec "$TARGET_CONTAINER" psql --username "$ADMIN_USER" --dbname "$DATABASE" --set=ON_ERROR_STOP=1 \
  --command "UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='48';" >/dev/null
app_environment "$TARGET_CONTAINER"
set +e
timeout 90 docker run --name "$TARGET_APP" --network "$NETWORK" --read-only --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --cap-drop ALL --security-opt no-new-privileges:true \
  --env SPRING_PROFILES_ACTIVE --env DATABASE_URL --env DATABASE_USERNAME --env DATABASE_PASSWORD \
  --env FLYWAY_URL --env FLYWAY_USER --env FLYWAY_PASSWORD \
  --env DATABASE_MAXIMUM_POOL_SIZE --env DATABASE_MINIMUM_IDLE --env DATABASE_CONNECTION_TIMEOUT_MILLISECONDS \
  --env SERVER_TOMCAT_THREADS_MAX --env SERVER_TOMCAT_MAX_CONNECTIONS --env SERVER_TOMCAT_ACCEPT_COUNT \
  --env SERVER_TOMCAT_CONNECTION_TIMEOUT --env JWT_KEY_ID --env JWT_SECRET --env STRIPE_SECRET_KEY \
  --env STRIPE_WEBHOOK_SECRET --env STRIPE_PUBLIC_KEY --env STRIPE_CONNECT_TIMEOUT --env STRIPE_READ_TIMEOUT \
  --env STRIPE_MAX_NETWORK_RETRIES "$APP_IMAGE" >/dev/null 2>&1
corrupt_status=$?
set -e
[[ "$corrupt_status" -ne 0 && "$corrupt_status" -ne 124 ]] \
  || fail "application accepted corrupted Flyway checksum"

commit_sha=$(git rev-parse HEAD 2>/dev/null || printf unknown)
printf '%s\n' \
  "historical_forward_restore_rehearsal=passed" "scenario=$HISTORICAL_SCENARIO" \
  "commit_sha=$commit_sha" "historical_version=$HISTORICAL_VERSION" "current_version=$CURRENT_VERSION" \
  "historical_migrations=$source_history" "pending_discovered=$expected_versions" \
  "pending_applied=$applied_versions" "source_postgresql=$source_version" "target_postgresql=$target_version" \
  "dump_sha256=$dump_digest" "dump_bytes=$dump_size" "backup_seconds=$backup_seconds" \
  "restore_seconds=$restore_seconds" "historical_checksums=preserved" "v49_snapshot_constraints=passed" \
  "v50_credential_version=passed" "ownership_privileges=passed" "v45_append_only=passed" \
  "application_readiness=passed" "corrupted_history_fail_closed=passed" "cleanup=armed"
