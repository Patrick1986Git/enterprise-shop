# Local development

The local Docker stack keeps PostgreSQL separate from any system PostgreSQL that may already listen on `localhost:5432`. Docker PostgreSQL remains loopback-bound on `127.0.0.1:${POSTGRES_HOST_PORT:-5433}` and continues to use the named volume `enterprise_shop_postgres_volume`.

## Local database identities

| Identity | Default variables | Purpose |
| --- | --- | --- |
| PostgreSQL admin/bootstrap | `POSTGRES_USER=postgres`, `POSTGRES_PASSWORD=postgres` | Owns the local database, provisions roles, and is used by local Flyway migrations that need schema privileges. |
| Application runtime | `APP_DB_USER=shop_dev`, `APP_DB_PASSWORD=shop_dev` | Used by the Spring datasource for normal application queries and transactions. It is `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION`. |

Host-run Spring Boot defaults to `jdbc:postgresql://localhost:5433/enterprise_shop_dev`. The full Compose app uses `jdbc:postgresql://postgres:5432/enterprise_shop_dev` for container-to-container networking. In the `dev` profile, the Spring datasource uses the runtime role while Flyway uses explicit `FLYWAY_URL`, `FLYWAY_USER`, and `FLYWAY_PASSWORD` settings that default to the local PostgreSQL admin identity.

## Database-only startup for Eclipse or Maven

```bash
docker compose up -d --wait postgres
docker compose up --wait database-role-bootstrap
```

After those commands complete, start Spring Boot from Eclipse or Maven with the `dev` profile. No YAML edits are required for the default host port and credentials.

## Full Compose startup

```bash
docker compose --profile full up -d --build --wait
```

The app service waits for PostgreSQL to become healthy and for `database-role-bootstrap` to complete successfully before starting.

## Bootstrap behavior and existing volumes

`database-role-bootstrap` is a one-shot service. It connects with the PostgreSQL admin account, creates the runtime role if missing, updates the local development password, grants runtime table, sequence, function, schema, and database privileges, and configures default privileges for future objects created by local Flyway. It is idempotent and can be rerun against fresh or existing `enterprise_shop_postgres_volume` data.

Existing named-volume users should run the database-only startup commands once after pulling this change. Do not delete or recreate the volume; no existing volume migration requires data deletion. Warning: `docker compose down -v` still removes Compose volumes, including `enterprise_shop_postgres_volume`, and deletes local Docker database data.

## Inspect roles and grants

```bash
docker compose exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-enterprise_shop_dev}" \
  -c "SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolreplication FROM pg_roles WHERE rolname = 'shop_dev';"

docker compose exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-enterprise_shop_dev}" \
  -c "SELECT grantee, privilege_type FROM information_schema.role_table_grants WHERE grantee = 'shop_dev' ORDER BY privilege_type;"
```

## Troubleshooting

- If `5433` is busy, set `POSTGRES_HOST_PORT` in `.env` or the shell and override host-run `DATABASE_URL` accordingly.
- If `8080` is busy, set `APP_HOST_PORT`, for example `APP_HOST_PORT=18080 docker compose --profile full up -d --build --wait`.
- Check readiness with `docker compose ps`, `docker compose logs postgres`, `docker compose logs database-role-bootstrap`, and `docker compose exec -T postgres pg_isready -U postgres -d enterprise_shop_dev`.
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
