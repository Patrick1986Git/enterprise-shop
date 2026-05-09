# Local development

## Prerequisites
- Java 21
- Maven
- Docker + Docker Compose

## 1) Start local PostgreSQL
```bash
docker compose up -d
```

This starts `enterprise-shop-db` on `localhost:5433` (mapped host port used by `application-dev.yml`).

## 2) Prepare database user/permissions
Run the SQL bootstrap script as a PostgreSQL superuser:

```bash
psql -h localhost -U postgres -d postgres -f scripts/db-setup.sql
```

For details about what the script creates and when to run it, see [database.md](./database.md#local-bootstrap-script).

## 3) Run the application
```bash
./mvnw spring-boot:run
```

Default active profile is `dev` (from `application.yml`).

## 4) Validate startup
- Root probe: `GET http://localhost:8080/api/v1`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`

## Important environment variables
- `JWT_SECRET` *(optional in local dev; if missing, app uses explicit dev-only fallback from `application-dev.yml`)*
- `STRIPE_SECRET_KEY` *(optional in local dev; defaults to placeholder value from `application.yml`)*
- `STRIPE_WEBHOOK_SECRET` *(optional in local dev; defaults to placeholder value from `application.yml`)*
- `STRIPE_PUBLIC_KEY` *(optional in local dev; defaults to placeholder value from `application.yml`)*

For production profile (`prod`), `JWT_SECRET`, `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` are required and have no defaults.

## 5) Recommended local verification
```bash
./mvnw clean verify
```

## Editor configuration baseline
This repository uses a root [`.editorconfig`](../../.editorconfig) as a lightweight, non-invasive baseline for line endings, encoding, and indentation defaults across editors.

## Observability (baseline)
- Actuator endpoints exposed in this project:
  - `GET /actuator/health` (public, no JWT)
  - `GET /actuator/info` (requires `ROLE_ADMIN`)
  - `GET /actuator/metrics` (requires `ROLE_ADMIN`)
- Request correlation header:
  - Send `X-Request-Id` to propagate your request identifier.
  - If header is missing or invalid, server generates UUID and returns it in response header `X-Request-Id`.
  - `X-Request-Id` is an exposed CORS response header for frontend clients.
- Logs include correlation id in pattern as `requestId=%X{requestId}`.
- Sensitive data policy:
  - do not log JWT tokens
  - do not log passwords
  - do not log card data or secrets
  - request/response body logging is intentionally disabled
