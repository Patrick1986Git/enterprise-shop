# Testing strategy

## Tooling

- Java 21.
- Maven Wrapper is present and should be used for repeatable local/CI commands.
- CI runs `./mvnw -B clean verify`.
- Test stack includes JUnit 5, AssertJ, Mockito, Spring MVC test support, Spring Security test support, Testcontainers PostgreSQL, Flyway test support, and ArchUnit.

Mockito static mocking is used in service tests, including Stripe SDK entry points. The build config starts tests with Mockito as an explicit `-javaagent` to avoid JDK 21+ dynamic self-attach warnings and keep execution compatible with stricter future JDK defaults.

## Current test categories

| Category | Current coverage examples |
| --- | --- |
| Unit tests | Services, entities/domain invariants, validators, DTO validation, mappers/facade behavior where applicable. |
| WebMvc/security/contract tests | Controllers, auth/security behavior, global error response shape, request-id behavior, actuator access. |
| Repository/DataJpa tests | Repository behavior and persistence constraints. |
| Testcontainers PostgreSQL integration tests | Persistence integration paths using PostgreSQL rather than an in-memory substitute. |
| Migration verification tests | Flyway smoke checks and schema-hardening migrations including constraints, snapshots, outbox, and schema artifacts. |
| ArchUnit architecture rules | Package/layering rules in `ArchitectureRulesTest`. |
| Observability/config/security tests | Request correlation, OpenAPI smoke, actuator security, security filter behavior, config validation. |
| Outbox and notification tests | Outbox event recording/processing/polling/properties, notification entity/service/outbox handler/delivery processor/repository. |
| Payment/webhook tests | Payment intent creation, webhook processing, webhook controller contract, and Stripe webhook persistence/idempotency. |

## Recommended commands

Run from the repository root:

```bash
./mvnw -B validate
./mvnw test
./mvnw clean verify
```

Targeted examples:

```bash
./mvnw -Dtest=ArchitectureRulesTest test
./mvnw -Dtest=OutboxEventProcessorTest test
./mvnw -Dtest=SecurityConfigWebMvcTest test
```

## Practical guidance

- Prefer the narrowest useful test for the changed behavior.
- Use WebMvc tests for endpoint contracts and security expectations.
- Use service/unit tests for business rules and orchestration.
- Use repository or PostgreSQL integration tests for persistence constraints and query behavior.
- Use migration verification tests for new Flyway schema objects, constraints, or data backfills.
- Keep tests focused; avoid broad fixture frameworks unless repeated duplication justifies them.
