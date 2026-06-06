# Local development

## Prerequisites

- Java 21.
- Maven Wrapper from this repository (`./mvnw` preferred).
- Docker and Docker Compose for local PostgreSQL.

## Local PostgreSQL

Start the database from the repository root:

```bash
docker compose up -d postgres
```

Docker Compose exposes PostgreSQL as:

| Setting | Value |
| --- | --- |
| Host | `localhost` |
| Port | `5433` |
| Database | `enterprise_shop_dev` |
| Username | `postgres` |
| Password | `postgres` |

The `dev` profile uses `jdbc:postgresql://localhost:5433/enterprise_shop_dev`, Flyway migrations, and Hibernate schema validation.

## Configuration

The default active profile is `dev` in `application.yml`.

Required for real payment/webhook flows:

| Variable | Purpose | Local fallback |
| --- | --- | --- |
| `JWT_SECRET` | JWT signing secret. | Development-only secret in `application-dev.yml`. |
| `STRIPE_SECRET_KEY` | Stripe server API key. | `sk_test_placeholder`. |
| `STRIPE_WEBHOOK_SECRET` | Stripe webhook signature secret. | `whsec_placeholder`. |
| `STRIPE_PUBLIC_KEY` | Stripe publishable key returned with payment intent data. | `pk_test_placeholder`. |

Production profile database variables are explicit: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `JWT_SECRET`.

## Build and run

Validate the toolchain and compile/test baseline:

```bash
./mvnw -B validate
./mvnw test
```

Run the application locally:

```bash
./mvnw spring-boot:run
```

The app listens on `http://localhost:8080` by default.

## Useful local URLs

| URL | Access | Purpose |
| --- | --- | --- |
| `http://localhost:8080/api/v1` | Public | Root API probe. |
| `http://localhost:8080/swagger-ui.html` | Authenticated unless covered by SpringDoc redirect/resource handling | Swagger UI configured path; `/swagger-ui/**` is the explicit public matcher. |
| `http://localhost:8080/swagger-ui/index.html` | Public | Swagger UI runtime path shown on startup. |
| `http://localhost:8080/api-docs` | Public | OpenAPI JSON. |
| `http://localhost:8080/actuator/health` | Public | Health smoke check. |
| `http://localhost:8080/actuator/prometheus` | Admin | Prometheus metrics endpoint. |

## Smoke checks

```bash
curl -i http://localhost:8080/api/v1
curl -i http://localhost:8080/actuator/health
curl -i -H "X-Request-Id: local-smoke" http://localhost:8080/api/v1/products
```

For admin actuator checks, authenticate as an admin user and pass `Authorization: Bearer <admin-token>`.
