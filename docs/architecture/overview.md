# Architecture overview

## Runtime stack
- Spring Boot 3.5.x application with Java 21.
- Layering built on Spring Web, Spring Security, Spring Data JPA, Flyway, and PostgreSQL.
- Stateless JWT authentication for protected endpoints.
- Stripe integration for payment intent creation and webhook reconciliation.

## Package layout
Root package: `com.company.shop`

- `module.*` — business capabilities (`cart`, `category`, `order`, `product`, `system`, `user`)
- `security` — auth endpoints, JWT token/filter/provider, current-user resolution
- `common` — base entities and global exception contract
- `config` — Spring configuration (security, OpenAPI, auditing, SQL functions)
- `validation` — custom validation annotation(s)

## Layering model used across modules
Most modules follow this structure:
- `controller` — HTTP boundary
- `dto` — request/response contracts
- `service` — business logic
- `repository` — persistence access
- `entity` — domain persistence model
- `mapper` — DTO/entity transformation
- `exception` — module-specific business failures

Product module additionally uses `specification` for dynamic query filtering.

## Cross-cutting patterns
- **Error contract:** centralized in `GlobalExceptionHandler` and `ApiError`.
- **Security:** global filter chain + method-level `@PreAuthorize`.
- **Persistence evolution:** Flyway migrations under `src/main/resources/db/migration`.
- **Auditing/soft delete:** shared base entity model in `common.model`.
