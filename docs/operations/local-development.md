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

The script requires Docker Compose and Python 3. Before any Compose startup or role bootstrap, a read-only preflight compares the installed `/usr/local/bin/gosu` with the existing [reviewed candidate receipt](../../.github/security/gosu/candidate.json). For an existing running or stopped service container, it copies only that executable to memory and starts the verified container with `--no-recreate --no-build --wait`. It never automatically replaces an existing database container. With no container and no existing PostgreSQL 18 named volume, it checks a cached image through `docker image save`, or allows the Dockerfile's hash-checked build when no image exists. A named volume without its service container requires operator review before startup.

After that preflight, the script checks whether the application role `${APP_DB_USER:-shop_dev}` can authenticate, has least-privilege role attributes, and has the required runtime database grants, and runs `database-role-bootstrap` only when the role is missing, authentication fails, attributes are unsafe, or required privileges are unavailable. Normal startup then uses `SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run` through `exec` so `Ctrl+C` is delivered directly to Spring Boot. `--prepare-only` performs the same database preparation and final validation, then exits successfully without starting Spring Boot.

The host application started by `run-dev.sh` always uses `APP_DB_USER` and `APP_DB_PASSWORD` for the datasource. `DATABASE_USERNAME` and `DATABASE_PASSWORD` must either be unset or match those runtime values; conflicting overrides fail fast instead of being ignored. `APP_DB_USER` must differ from `POSTGRES_USER`, so the host application cannot run as the PostgreSQL administrator. Flyway remains separate and continues to use `FLYWAY_USER` and `FLYWAY_PASSWORD`, which default to the local admin/bootstrap identity.

The script reads default values from the shell and from a local `.env` file without sourcing or executing `.env` content. Shell environment variables take precedence over `.env`, and `.env` takes precedence over built-in defaults. The supported `.env` subset is intentionally small: blank lines, full-line comments starting with `#` after optional leading whitespace, optional `export KEY=VALUE`, unquoted values, single-quoted values, double-quoted values, and empty values. Invalid variable names, unsupported lines, unterminated quotes, and command substitution syntax such as backticks or `$(` are rejected with a clear error.

JWT configuration uses a non-secret `JWT_KEY_ID` plus `JWT_SECRET` as standard RFC 4648 Base64 encoding of at least 32 key bytes. Existing local `.env` files may use the development values from `.env.example`, or generate separate non-production key material with `openssl rand -base64 32`; never commit a real deployment secret. Optional `JWT_PREVIOUS_KEY_ID` and `JWT_PREVIOUS_SECRET` are only for exercising the bounded rollover path documented in `docs/operations/jwt-key-rotation.md`.

On a fresh `enterprise_shop_postgres18_volume`, `run-dev.sh --prepare-only` creates `shop_dev`, sets its password, applies runtime grants, and configures default privileges for objects created by local Flyway. On an existing PostgreSQL 18 volume, the same command validates the persisted role and grants and skips bootstrap when everything is already correct. If local credentials change or the role temporarily receives unsafe attributes such as `CREATEDB`, the script runs the idempotent bootstrap to repair the password and restore `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION` before rechecking.

Role definitions, passwords, grants, and local data are stored in `enterprise_shop_postgres18_volume`. `docker compose down` stops and removes containers and networks while keeping that named volume. `docker compose down -v` removes the PostgreSQL 18 volume and deletes that local database state; use it only when you intentionally want a fresh local database. It does not remove the separately named legacy PostgreSQL 16 volume.

Manual bootstrap is normally required only for a new `enterprise_shop_postgres18_volume`, changed local credentials, or changed role privileges. For day-to-day startup, prefer `./scripts/run-dev.sh`; it decides whether the bootstrap one-shot task is necessary.

## Database-only startup for Eclipse or manual Maven runs

```bash
./scripts/run-dev.sh --prepare-only
```

After preparation completes, start Spring Boot from Eclipse or Maven with the `dev` profile. No YAML edits are required for the default host port and credentials. `database-role-bootstrap` remains a one-shot administrative task; successful termination with exit code 0 is expected, and `--rm` removes its temporary container.

## Adopting a reviewed PostgreSQL image

A committed Dockerfile and successful CI describe a newly built image. They do not change an executable inside an existing local container. Compose can reuse a running container, restart a stopped one, or create a container from a cached image without rebuilding after a Dockerfile-only change. The startup preflight therefore checks actual executable bytes, including stopped containers, rather than trusting a tag, label or version string. Missing/unreadable evidence, an unexpected digest, unsupported architecture, or Docker failure stops startup before Compose or database-role bootstrap can mutate anything.

The check establishes the reviewed gosu identity only; it is not an image-wide vulnerability scan or a new provenance attestation. It supports the receipt's `linux/amd64` platform. Cached-image inspection uses temporary local disk space for an image archive and rejects unsupported links or ambiguous archive layouts. Run it without concurrent image/container changes. Direct Compose commands bypass this safeguard; use `run-dev.sh` for ordinary local startup.

Image adoption is a separate, operator-controlled maintenance operation. Do not run the following procedure as an automatic startup repair:

1. Identify the intended Compose project and PostgreSQL 18 service. Confirm its existing mount is `enterprise_shop_postgres18_volume` at `/var/lib/postgresql`; preserve the current image identity for rollback. Do not dump container configuration or credentials into logs.
2. Secure a current verified backup and independently preserved role, credential and ADMIN recovery provisioning outside the repository. Follow the [disaster recovery runbook](disaster-recovery.md) and [local administrator runbook](local-administrator.md). Verify recoverability using a separate disposable target before scheduling downtime; keep locally preserved backups intact.
3. Build the current reviewed image before downtime, then check its executable without creating or starting a container:

   ```bash
   docker compose build postgres
   python3 scripts/check-local-postgres-gosu.py --image "$(docker compose config --images postgres)"
   ```

4. Once the operator has approved maintenance, stop application writers. Explicitly replace only the PostgreSQL service using the reviewed local image and the unchanged named volume:

   ```bash
   docker compose up -d --no-deps --no-build --force-recreate --wait postgres
   ./scripts/run-dev.sh --prepare-only
   ```

5. Check health, runtime-role validation and the expected Flyway history before resuming writers. Retain the backup, recovery material and previous image until the application and persistent data are verified. Do not initialize a new cluster, change PostgreSQL major versions, delete the volume, or use volume-removing/prune commands as part of adoption.

Reattaching the unchanged volume is not backup restoration and does not require V52/V53 recovery invalidation. If an actual restore becomes necessary, follow the restore and ADMIN recovery contracts in the linked runbooks, including their explicit invalidation and reprovisioning steps. This procedure does not authorize automated live replacement.

## Full Compose startup

```bash
docker compose --profile full up -d --build --wait
```

The app service waits for PostgreSQL to become healthy and for `database-role-bootstrap` to complete successfully before starting. This direct Compose command can replace existing containers and bypasses the host-startup preflight. For an existing persistent development database, use the reviewed-image maintenance procedure above before intentionally updating the full stack.

## Local Swagger UI authentication

After startup, open `http://localhost:8080/swagger-ui/index.html` and select the generated authentication operations.
Register an ordinary account, then log in. These operations require no CSRF token or preliminary cookie/session request.
Use Swagger UI's **Authorize** control with the returned JWT for protected operations; the UI supplies the bearer header.
Other API clients must explicitly supply that same header. Cookies alone do not sign a client in.

Registration never creates an administrator. Use the separately authorized [local administrator runbook](./local-administrator.md)
for that lifecycle. Do not copy passwords, JWTs, or request bodies into logs or shared artifacts. Runtime Swagger UI and
OpenAPI remain disabled in production. The [security architecture](../architecture/security-architecture.md) records the
API-scoped CSRF decision and the review required before introducing automatically supplied authentication credentials.

## Maven and Testcontainers verification

Use a Java 21 JDK and the checked-in Maven Wrapper. Unit tests do not need the Ryuk build or Go analysis tooling:

```bash
./mvnw test
```

The complete verification lifecycle also needs Python 3, Git, a running Docker daemon capable of linux/amd64 execution, and network access to GitHub, Docker Hub and Go module/checksum services. PostgreSQL integration tests use their own Testcontainers database, independently of the Compose database. A fresh checkout must build the unpublished reviewed Ryuk image before Maven:

```bash
python3 scripts/build-ryuk-candidate.py --no-analysis
./mvnw -B clean verify
python3 scripts/check-ryuk-cleanup.py
```

Preparation verifies the pinned upstream archive and patch, immutable Go 1.26.10 Alpine 3.24 builder, repeated executable bytes, module checksums, selected upstream cleanup tests and exact scratch image contents against the reviewed candidate receipt. It loads the resulting local image; it publishes nothing and never falls back to official Ryuk. `--no-analysis` omits downloading/compiling govulncheck and contacting the Go advisory database for local preparation. Hosted CI and CodeQL omit that flag and retain mandatory source/binary vulnerability analysis; local preparation is not a security approval. A network restriction affecting `vuln.go.dev` leaves local advisory verification unverified and requires successful hosted evidence for the exact final HEAD.

The Go 1.26.10 compiler repair responds to [GO-2026-6809 / CVE-2026-111943](https://pkg.go.dev/vuln/GO-2026-6809), published after the previous Go 1.26.9 verification. It retains the reviewed Ryuk program source and dependencies while changing executable and image identities. Historical clean reports do not authorize reuse of that older image. Choose a new preparation directory after updating this security change; the current candidate still requires fresh hosted analysis and runtime verification. The coordinated gosu and Trivy compiler changes and their trust boundaries are described in [container security](container-security.md#reviewed-unpublished-ryuk-rebuild).

To reuse an existing preparation for the same HEAD, revalidate its complete build evidence and loaded image before running Maven:

```bash
python3 scripts/build-ryuk-candidate.py --no-analysis --reuse
./mvnw -B clean verify
python3 scripts/check-ryuk-cleanup.py
```

Missing, changed or incomplete evidence, another HEAD, or an incorrect loaded image configuration fails reuse. Choose a new directory with `--directory .tmp/ryuk-candidate-new` when rebuilding; use that same directory with `--reuse` afterward. An absent local Ryuk image fails Testcontainers startup with the preparation command instead of attempting a private-registry pull. Runtime integration assertions also check the actual candidate image ID and Docker socket, and the separate cleanup command waits for resources to disappear after the Maven JVM exits. On an arm64 workstation Docker must support amd64 emulation; native arm64 candidate builds are not reviewed.

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
