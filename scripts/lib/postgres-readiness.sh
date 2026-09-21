#!/usr/bin/env bash

postgres_readiness_diagnostics() {
  local container=$1
  printf 'PostgreSQL container state (%s):\n' "$container" >&2
  docker inspect --format \
    'status={{.State.Status}} running={{.State.Running}} exit_code={{.State.ExitCode}} error={{json .State.Error}}' \
    "$container" >&2 || printf 'container inspection failed\n' >&2
  printf 'PostgreSQL container log tail (%s):\n' "$container" >&2
  docker logs --tail 100 "$container" >&2 || printf 'container log collection failed\n' >&2
}

wait_for_final_postgres() {
  local container=$1 admin_user=$2 attempts=${3:-60}
  local attempt state pid_one sql_user

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    state=$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null) || state=unknown
    if [[ "$state" != true ]]; then
      postgres_readiness_diagnostics "$container"
      return 1
    fi

    # The official image runs its entrypoint as PID 1 while a socket-only bootstrap
    # server is active, then execs the final postgres process after bootstrap stops.
    pid_one=$(docker exec "$container" cat /proc/1/comm 2>/dev/null) || pid_one=
    if [[ "$pid_one" == postgres ]]; then
      sql_user=$(docker exec "$container" psql --username "$admin_user" --dbname postgres \
        --tuples-only --no-align --set=ON_ERROR_STOP=1 --command 'SELECT current_user;' 2>/dev/null) \
        || sql_user=
      if [[ "$sql_user" == "$admin_user" ]]; then
        return 0
      fi
    fi
    sleep 1
  done

  postgres_readiness_diagnostics "$container"
  return 1
}
