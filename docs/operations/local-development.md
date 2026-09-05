# Local development

The local Docker stack keeps PostgreSQL separate from any system PostgreSQL that may already listen on `localhost:5432`. Docker PostgreSQL 18 remains loopback-bound on `127.0.0.1:${POSTGRES_HOST_PORT:-5433}` and uses the versioned named volume `enterprise_shop_postgres18_volume`.

## Local database identities

| Identity | Default variables | Purpose |
| --- | --- | --- |
| PostgreSQL admin/bootstrap | `POSTGRES_USER=postgres`, `POSTGRES_PASSWORD=postgres` | Owns the local database, provisions roles, and is used by local Flyway migrations that need schema privileges. |
| Application runtime | `APP_DB_USER=shop_dev`, `APP_DB_PASSWORD=shop_dev` | Used by the Spring datasource for normal application queries and transactions. It is `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION`. |

Host-run Spring Boot defaults to `jdbc:postgresql://localhost:5433/enterprise_shop_dev`. The full Compose app uses `jdbc:postgresql://postgres:5432/enterprise_shop_dev` for container-to-container networking. In the `dev` profile, the Spring datasource uses the runtime role while Flyway uses explicit `FLYWAY_URL`, `FLYWAY_USER`, and `FLYWAY_PASSWORD` settings that default to the local PostgreSQL admin identity.

## One-command host startup

Run the local development environment with Spring Boot on the host:

```bash
./scripts/run-dev.sh
```

You can invoke the script from the repository root or by absolute/relative path from another directory; it resolves the repository root and runs `.env` loading, Docker Compose, and Maven from that root.

To prepare only PostgreSQL and the runtime role without starting Maven or Spring Boot, run:

```bash
./scripts/run-dev.sh --prepare-only
```

The script starts PostgreSQL with `docker compose up -d --wait postgres`, checks whether the application role `${APP_DB_USER:-shop_dev}` can authenticate, has least-privilege role attributes, and has the required runtime database grants, and runs `database-role-bootstrap` only when the role is missing, authentication fails, attributes are unsafe, or required privileges are unavailable. Normal startup then uses `SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run` through `exec` so `Ctrl+C` is delivered directly to Spring Boot. `--prepare-only` performs the same database preparation and final validation, then exits successfully without starting Spring Boot.

The host application started by `run-dev.sh` always uses `APP_DB_USER` and `APP_DB_PASSWORD` for the datasource. `DATABASE_USERNAME` and `DATABASE_PASSWORD` must either be unset or match those runtime values; conflicting overrides fail fast instead of being ignored. `APP_DB_USER` must differ from `POSTGRES_USER`, so the host application cannot run as the PostgreSQL administrator. Flyway remains separate and continues to use `FLYWAY_USER` and `FLYWAY_PASSWORD`, which default to the local admin/bootstrap identity.

The script reads default values from the shell and from a local `.env` file without sourcing or executing `.env` content. Shell environment variables take precedence over `.env`, and `.env` takes precedence over built-in defaults. The supported `.env` subset is intentionally small: blank lines, full-line comments starting with `#` after optional leading whitespace, optional `export KEY=VALUE`, unquoted values, single-quoted values, double-quoted values, and empty values. Invalid variable names, unsupported lines, unterminated quotes, and command substitution syntax such as backticks or `$(` are rejected with a clear error.

After the JWT signing-key contract change, an existing local `.env` must set `JWT_SECRET` to standard RFC 4648 Base64 encoding of at least 32 key bytes. Replace the former raw development placeholder with the value from `.env.example`, or generate separate non-production key material with `openssl rand -base64 32`; never commit a real deployment secret.

On a fresh `enterprise_shop_postgres18_volume`, `run-dev.sh --prepare-only` creates `shop_dev`, sets its password, applies runtime grants, and configures default privileges for objects created by local Flyway. On an existing PostgreSQL 18 volume, the same command validates the persisted role and grants and skips bootstrap when everything is already correct. If local credentials change or the role temporarily receives unsafe attributes such as `CREATEDB`, the script runs the idempotent bootstrap to repair the password and restore `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION` before rechecking.

Role definitions, passwords, grants, and local data are stored in `enterprise_shop_postgres18_volume`. `docker compose down` stops and removes containers and networks while keeping that named volume. `docker compose down -v` removes the PostgreSQL 18 volume and deletes that local database state; use it only when you intentionally want a fresh local database. It does not remove the separately named legacy PostgreSQL 16 volume.

Manual bootstrap is normally required only for a new `enterprise_shop_postgres18_volume`, changed local credentials, or changed role privileges. For day-to-day startup, prefer `./scripts/run-dev.sh`; it decides whether the bootstrap one-shot task is necessary.

## Database-only startup for Eclipse or manual Maven runs

```bash
docker compose up -d --wait postgres
docker compose run --rm --no-deps --build database-role-bootstrap
```

PostgreSQL is a long-running service, so it uses `up --wait`. `database-role-bootstrap` is a one-shot administrative task, so it uses `run`; successful termination with exit code 0 is expected, and `--rm` removes the temporary one-off container. After those commands complete, start Spring Boot from Eclipse or Maven with the `dev` profile. No YAML edits are required for the default host port and credentials.

## Full Compose startup

```bash
docker compose --profile full up -d --build --wait
```

The app service waits for PostgreSQL to become healthy and for `database-role-bootstrap` to complete successfully before starting.

## PostgreSQL 16 to 18 local-volume migration

The PostgreSQL 18 official image stores version-specific clusters below `/var/lib/postgresql`, so Compose mounts its new volume at that parent path. PostgreSQL 16 used the `/var/lib/postgresql/data` mount. A PostgreSQL 16 data directory is not binary-compatible with PostgreSQL 18 and must never be opened directly by the PostgreSQL 18 server.

Compose therefore creates `enterprise_shop_postgres18_volume` and leaves the legacy `enterprise_shop_postgres_volume` untouched. If the old data is disposable, simply run `./scripts/run-dev.sh --prepare-only`; this initializes a fresh PostgreSQL 18 cluster and applies Flyway migrations when the application starts. Do not delete the old volume as part of startup.

To preserve local data, migrate it logically rather than reusing the old volume. Stop the Compose database, start a temporary PostgreSQL 16 server against the legacy volume, dump the application database, stop that server, initialize PostgreSQL 18, and restore the dump:

```bash
docker compose down
docker run -d --rm --name enterprise-shop-postgres16-migration \
  -e POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres}" \
  -p 127.0.0.1:5434:5432 \
  -v enterprise_shop_postgres_volume:/var/lib/postgresql/data \
  postgres:16-alpine
until docker exec enterprise-shop-postgres16-migration pg_isready -U "${POSTGRES_USER:-postgres}"; do sleep 1; done
docker exec enterprise-shop-postgres16-migration \
  pg_dump -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-enterprise_shop_dev}" -Fc \
  > enterprise-shop-postgres16.dump
docker stop enterprise-shop-postgres16-migration

./scripts/run-dev.sh --prepare-only
docker compose exec -T postgres \
  pg_restore -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-enterprise_shop_dev}" \
  --clean --if-exists --no-owner < enterprise-shop-postgres16.dump
docker compose run --rm --no-deps database-role-bootstrap
```

Keep the dump until the restored application and data have been verified. Adjust credentials and database names if the legacy cluster did not use the defaults. The source volume remains unchanged throughout, providing a rollback source.

## Bootstrap behavior

`database-role-bootstrap` is a one-shot service. It connects to `postgres:5432` with the PostgreSQL admin account, creates the runtime role if missing, updates the local development password, grants runtime table, sequence, function, schema, and database privileges, and configures default privileges for future objects created by local Flyway. It is idempotent and can be rerun against fresh or existing `enterprise_shop_postgres18_volume` data.

## Inspect roles and grants

```bash
docker compose exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-enterprise_shop_dev}" \
  -c "SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication FROM pg_roles WHERE rolname = 'shop_dev';"

docker compose exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-enterprise_shop_dev}" \
  -c "SELECT grantee, privilege_type FROM information_schema.role_table_grants WHERE grantee = 'shop_dev' ORDER BY privilege_type;"
```

## Troubleshooting

- If `5433` is busy, set `POSTGRES_HOST_PORT` in `.env` or the shell and override host-run `DATABASE_URL` accordingly.
- If `8080` is busy, set `APP_HOST_PORT`, for example `APP_HOST_PORT=18080 docker compose --profile full up -d --build --wait`.
- Check readiness with `docker compose ps`, `docker compose logs postgres`, the streamed bootstrap command output, and `docker compose exec -T postgres pg_isready -U postgres -d enterprise_shop_dev`.
- Docker and Testcontainers may create temporary overlay mounts named `merged`. These are not disk partitions and must not be edited manually.

## Useful local URLs

| URL | Access | Purpose |
| --- | --- | --- |
| `http://localhost:8080/api/v1` | Public | Root API probe. |
| `http://localhost:8080/swagger-ui.html` | Authenticated unless covered by SpringDoc redirect/resource handling | Swagger UI configured path; `/swagger-ui/**` is the explicit public matcher. |
| `http://localhost:8080/swagger-ui/index.html` | Public | Swagger UI runtime path shown on startup. |
| `http://localhost:8080/api-docs` | Public | OpenAPI JSON. |
| `http://localhost:8080/actuator/health` | Public | Health smoke check. |
| `http://localhost:8080/actuator/prometheus` | Admin | Prometheus metrics endpoint. |

For admin actuator checks, authenticate as an admin user and pass `Authorization: Bearer <admin-token>`.
