# Architecture overview

## Runtime stack

enterprise-shop is a Java 21 modular monolith built on the current repository stack:

- Spring Boot 4.0.x
- Spring Web MVC
- Spring Security with stateless JWT authentication
- Spring Data JPA / Hibernate
- Flyway-managed PostgreSQL schema evolution
- PostgreSQL
- SpringDoc/OpenAPI
- Spring Boot Actuator with Prometheus registry
- Micrometer application metrics
- Stripe integration for payment intent creation and webhook processing

## Application model

The application is deployed as one Spring Boot service. Business capabilities are separated by package and layer rather than by independently deployed services. This keeps transactional boundaries local while preserving clear module ownership.

`ShopApplication` enables component scanning and `@ConfigurationPropertiesScan`, which binds configuration properties such as outbox processing settings. Scheduling is enabled through `SchedulingConfig`; the current scheduled use is the outbox poller.

## Package layout

Root package: `com.company.shop`

| Area | Purpose |
| --- | --- |
| `module.cart` | Authenticated shopper cart lifecycle and checkout cart snapshot facade. |
| `module.category` | Category tree/catalog classification. |
| `module.order` | Checkout, orders, payments, Stripe webhook handling, and order outbox. |
| `module.product` | Product catalog, search, stock reservation facade, and reviews. |
| `module.system` | Root/status API probes. |
| `module.user` | User profile, admin user management, and current-user facade. |
| `module.notification` | Internal notification records and no-op delivery baseline. |
| `common` | Shared exceptions, API error model, base entities, i18n support, and request correlation filter. |
| `config` | Spring configuration for security, OpenAPI, auditing, scheduling, i18n, and SQL functions. |
| `security` | Authentication endpoints/services, JWT filter/provider, roles, and current-user resolution. |
| `validation` | Custom Bean Validation annotations and validators. |

## Layering model

Most business modules use the same package pattern when applicable:

- `controller` — HTTP boundary and request validation delegation.
- `dto` — request/response API contracts.
- `service` — business orchestration and transactional behavior.
- `repository` — persistence access.
- `entity` — JPA persistence model and domain invariants.
- `mapper` — DTO/entity transformation.
- `exception` — module-specific `BusinessException` types.
- `api.internal` — narrow internal facades used by other modules.
- `specification` — dynamic query logic where justified; currently used by product search.

## Cross-cutting patterns

- **API contracts:** controllers return DTOs rather than exposing JPA entities directly.
- **Error handling:** `GlobalExceptionHandler` maps framework and `BusinessException` failures to `ApiError`.
- **Security:** global filter chain plus method-level `@PreAuthorize` guards.
- **Persistence:** Flyway migrations under `src/main/resources/db/migration`; historical migrations are not edited.
- **Observability:** `X-Request-Id` correlation is stored in MDC as `requestId`, returned to clients, and included in the console log pattern.
- **Metrics:** Micrometer counters currently cover checkout, payment intents, Stripe webhooks, and business exceptions.
