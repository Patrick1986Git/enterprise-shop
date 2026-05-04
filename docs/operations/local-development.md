# Local development

## Prerequisites
- Java 21
- Maven
- Docker + Docker Compose

## 1) Start local PostgreSQL
```bash
docker compose up -d
```

This starts `enterprise-shop-db` on `localhost:5432`.

## 2) Prepare database user/permissions
Run the SQL bootstrap script as a PostgreSQL superuser:

```bash
psql -h localhost -U postgres -d postgres -f scripts/db-setup.sql
```

For details about what the script creates and when to run it, see [database.md](./database.md#local-bootstrap-script).

## 3) Run the application
```bash
mvn spring-boot:run
```

Default active profile is `dev` (from `application.yml`).

## 4) Validate startup
- Root probe: `GET http://localhost:8080/api/v1`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`

## Important environment variables
- `STRIPE_SECRET_KEY`
- `STRIPE_WEBHOOK_SECRET`
- `STRIPE_PUBLIC_KEY`
- `JWT_SECRET`

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
