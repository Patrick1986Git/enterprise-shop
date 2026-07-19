# Local development

## Prerequisites

- Java 21.
- Maven Wrapper from this repository (`./mvnw` preferred).
- Docker and Docker Compose for local PostgreSQL or the optional full stack.

## PostgreSQL port model

The host may already run a system PostgreSQL server on `localhost:5432`. The Docker PostgreSQL service therefore binds only to loopback on `localhost:5433` by default and keeps using the named volume `enterprise_shop_postgres_volume`.

| Client | JDBC URL |
| --- | --- |
| Spring Boot running on the host/Eclipse | `jdbc:postgresql://localhost:5433/enterprise_shop_dev` |
| Spring Boot running inside Compose | `jdbc:postgresql://postgres:5432/enterprise_shop_dev` |

Do not use `localhost` for container-to-container database traffic; inside Compose, `localhost` means the application container itself.

## Database-only workflow

Use this when running the Spring Boot application from Eclipse or Maven on the host:

```bash
docker compose up -d --wait postgres
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

Default local database settings remain:

| Setting | Value |
| --- | --- |
| Host | `localhost` |
| Port | `5433` |
| Database | `enterprise_shop_dev` |
| Username | `postgres` |
| Password | `postgres` |

The `dev` profile uses Flyway migrations and Hibernate schema validation. `application-dev.yml` keeps these defaults but allows `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` environment overrides.

## Optional full-stack Compose workflow

Use this to run both PostgreSQL and the Spring Boot application in containers:

```bash
docker compose --profile full up -d --build --wait
curl -i http://127.0.0.1:8080/actuator/health
```

The app service is profile-gated with `full`, publishes only to `127.0.0.1:${APP_HOST_PORT:-8080}`, activates the `dev` Spring profile, and connects to PostgreSQL through `jdbc:postgresql://postgres:5432/enterprise_shop_dev`.

## Local overrides

Copy `.env.example` to `.env` only for local overrides. `.env` is ignored by Git. The example contains development placeholders only; do not put production credentials in this repository.

## Useful Docker commands

```bash
docker compose config
docker compose ps
docker compose logs postgres
docker compose --profile full logs app
docker compose port postgres 5432
docker network inspect enterprise-shop_enterprise-shop-network
docker volume inspect enterprise_shop_postgres_volume
```

Stop containers without deleting database data:

```bash
docker compose --profile full down
```

Warning: `docker compose down -v` removes Compose volumes, including `enterprise_shop_postgres_volume`, and deletes the local Docker database data.

## Troubleshooting

- If `5433` is busy, set `POSTGRES_HOST_PORT` in `.env` or the shell, for example `POSTGRES_HOST_PORT=15433 docker compose up -d --wait postgres`, and override the host-run `DATABASE_URL` accordingly.
- If `8080` is busy, set `APP_HOST_PORT`, for example `APP_HOST_PORT=18080 docker compose --profile full up -d --build --wait`.
- Check readiness with `docker compose ps`, `docker compose logs postgres`, and `docker compose exec -T postgres pg_isready -U postgres -d enterprise_shop_dev`.
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
