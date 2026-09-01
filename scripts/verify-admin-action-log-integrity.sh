#!/bin/sh
set -eu

: "${POSTGRES_DB:=enterprise_shop_dev}"
: "${POSTGRES_USER:=postgres}"
: "${APP_DB_USER:=shop_dev}"
: "${APP_DB_PASSWORD:=shop_dev}"

case "$APP_DB_USER" in
  *[!A-Za-z0-9_]*|'')
    echo "APP_DB_USER must contain only letters, numbers, and underscores" >&2
    exit 1
    ;;
esac

docker compose run --rm --no-deps database-role-bootstrap >/dev/null

psql_admin() {
  docker compose exec -T postgres psql \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --quiet \
    --set=ON_ERROR_STOP=1 \
    "$@"
}

psql_app() {
  docker compose exec -T \
    -e "PGPASSWORD=$APP_DB_PASSWORD" \
    postgres psql \
      --host 127.0.0.1 \
      --port 5432 \
      --username "$APP_DB_USER" \
      --dbname "$POSTGRES_DB" \
      --quiet \
      --set=VERBOSITY=verbose \
      --set=ON_ERROR_STOP=1 \
      "$@"
}

assert_mutation_denied() {
  statement=$1
  if psql_app --command "$statement" >/tmp/admin-action-log-denial.out 2>&1; then
    echo "Expected runtime mutation to be denied: $statement" >&2
    exit 1
  fi
  grep -q "append-only for runtime roles" /tmp/admin-action-log-denial.out
  grep -Eq 'ERROR:[[:space:]]+42501:' /tmp/admin-action-log-denial.out
}

[ "$(psql_app --tuples-only --no-align --command "
  SELECT bool_and(has_table_privilege(current_user, table_name, 'SELECT,INSERT,UPDATE,DELETE'))
  FROM (VALUES
    ('notification_admin_action_logs'),
    ('outbox_event_admin_action_logs'),
    ('reservation_expiration_admin_action_logs')
  ) AS protected(table_name);")" = "t" ]

notification_id=$(psql_app --tuples-only --no-align --command \
  "INSERT INTO notification_admin_action_logs (notification_id, action_type, actor_email, details)
   VALUES (uuid_generate_v4(), 'REQUEUE', 'integrity-test@example.com', 'test-owned row') RETURNING id;")
outbox_id=$(psql_app --tuples-only --no-align --command \
  "INSERT INTO outbox_event_admin_action_logs (outbox_event_id, action_type, actor_email, details)
   VALUES (uuid_generate_v4(), 'REQUEUE', 'integrity-test@example.com', 'test-owned row') RETURNING id;")
reservation_id=$(psql_app --tuples-only --no-align --command \
  "INSERT INTO reservation_expiration_admin_action_logs
     (order_id, work_id, action_type, outcome, actor_email, created_at)
   VALUES (uuid_generate_v4(), uuid_generate_v4(), 'RECOVERY', 'TERMINAL_NOOP',
     'integrity-test@example.com', CURRENT_TIMESTAMP) RETURNING id;")

for table_and_id in \
  "notification_admin_action_logs:$notification_id" \
  "outbox_event_admin_action_logs:$outbox_id" \
  "reservation_expiration_admin_action_logs:$reservation_id"
do
  table=${table_and_id%%:*}
  id=${table_and_id#*:}
  [ "$(psql_app --tuples-only --no-align --command "SELECT count(*) FROM $table WHERE id = '$id';")" = "1" ]
  assert_mutation_denied "UPDATE $table SET actor_email = 'mutated@example.com' WHERE id = '$id';"
  [ "$(psql_admin --tuples-only --no-align --command "SELECT actor_email FROM $table WHERE id = '$id';")" = "integrity-test@example.com" ]
  assert_mutation_denied "DELETE FROM $table WHERE id = '$id';"
  [ "$(psql_admin --tuples-only --no-align --command "SELECT count(*) FROM $table WHERE id = '$id';")" = "1" ]
  psql_admin --command "UPDATE $table SET actor_email = 'owner-maintenance@example.com' WHERE id = '$id';" >/dev/null
  [ "$(psql_admin --tuples-only --no-align --command "SELECT actor_email FROM $table WHERE id = '$id';")" = "owner-maintenance@example.com" ]
  psql_admin --command "DELETE FROM $table WHERE id = '$id';" >/dev/null
  [ "$(psql_admin --tuples-only --no-align --command "SELECT count(*) FROM $table WHERE id = '$id';")" = "0" ]
done

category_id=$(psql_app --tuples-only --no-align --command \
  "INSERT INTO categories (name, slug) VALUES ('Integrity test category', 'integrity-test-category') RETURNING id;")
psql_app --command "UPDATE categories SET description = 'updated' WHERE id = '$category_id';" >/dev/null
psql_app --command "DELETE FROM categories WHERE id = '$category_id';" >/dev/null
[ "$(psql_admin --tuples-only --no-align --command "SELECT count(*) FROM categories WHERE id = '$category_id';")" = "0" ]

echo "Runtime audit INSERT/SELECT succeeded, UPDATE/DELETE was denied with SQLSTATE 42501, owner maintenance succeeded, and ordinary DML remained available."
