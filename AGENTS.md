# AGENTS.md

## Project overview

This repository contains a modular Spring Boot backend for an enterprise-grade shop application.

The codebase is organized primarily by business modules under:

`com.company.shop.module`

Main business modules currently include:

- cart
- category
- order
- notification
- product
- system
- user

There is also shared infrastructure and cross-cutting code under:

- `common` for shared exceptions and base models
- `config` for application configuration
- `security` for authentication, authorization, JWT, and current-user handling
- `validation` for custom validation rules and annotations

This project is being developed toward an enterprise-grade standard.  
Changes should favor correctness, consistency, maintainability, clear boundaries, and safe reviewable evolution over speed or novelty.

---

## Core working principles

When modifying this repository, always prefer:

- minimal, safe, reviewable changes
- consistency with the existing codebase
- explicit and readable code
- preserving architectural boundaries
- preserving existing behavior unless the task explicitly requires a change
- reusing an existing local pattern before introducing a new abstraction

Do not optimize for cleverness.  
Do not redesign the codebase unless explicitly asked.  
Do not introduce broad refactors when a focused change is sufficient.

---

## What to do before changing code

Before writing or modifying code:

1. Inspect the surrounding module and existing implementation style.
2. Reuse the current architectural and naming patterns when possible.
3. Make the smallest change that fully solves the requested problem.
4. Avoid touching unrelated files, tests, or modules.
5. If the requested change touches security, persistence, transactions, migrations, or public API behavior, treat it as high risk and validate carefully.

---

## Package organization rules

New business functionality must be placed in the correct module under:

`com.company.shop.module.<module-name>`

Examples:

- cart-related business logic belongs under `module.cart`
- product-related business logic belongs under `module.product`
- order-related business logic belongs under `module.order`
- notification-related business logic belongs under `module.notification`

Shared technical code belongs in:

- `common`
- `config`
- `security`
- `validation`

only when it is truly cross-cutting and not owned by a single business module.

### Important package constraints

- Do not move existing classes across modules unless explicitly required.
- Do not place business logic into shared technical packages.
- Do not create generic `util`, `helper`, or `common` classes unless there is a real repeated need.
- Do not introduce unnecessary dependencies between business modules.
- Keep module boundaries clear and intentional.

---

## Layering conventions

Within each module, follow the existing structure where applicable:

- `controller`
- `dto`
- `entity`
- `exception`
- `mapper`
- `repository`
- `service`
- `specification` when query logic justifies it

### Responsibilities

#### Controllers
- Handle HTTP concerns only.
- Validate request shape and delegate to services.
- Do not contain business logic.
- Do not perform persistence work directly.
- Do not contain mapping logic beyond trivial orchestration if an existing mapper already exists.

#### Services
- Contain business logic and orchestration.
- Enforce business rules.
- Define transactional behavior where needed.
- Coordinate repositories, mappers, and collaborators.

#### Repositories
- Handle persistence concerns only.
- Keep them focused on data access.
- Do not move business decisions into repositories.

#### Mappers
- Convert between entities and DTOs.
- Reuse existing mapper style and placement.
- Do not duplicate mapping logic across layers.

#### Entities
- Represent domain state and invariants where appropriate.
- Must not contain controller or transport concerns.
- Must not depend on DTOs.
- Keep JPA mappings aligned with existing conventions.

#### DTOs
- Represent API request and response contracts.
- Must not contain persistence logic.
- Should not contain service-level business behavior.

---

## Architectural patterns to preserve

The current architecture intentionally uses:

- DTO-based API design
- module-oriented package structure
- dedicated mapper classes/interfaces
- domain-specific exceptions
- centralized exception handling in `GlobalExceptionHandler`
- constructor injection
- Spring Security with JWT authentication
- Flyway-based schema evolution
- explicit request/response models instead of exposing entities directly

Preserve these patterns unless explicitly asked to change them.

---

## Existing-pattern-first rule

Before adding any new class, abstraction, helper, or pattern:

- first inspect the surrounding module
- look for an existing equivalent implementation
- follow the local style already present in the repository

Prefer extending an existing pattern over inventing a new one.

Examples:

- if a module already uses a mapper, do not inline mapping in the controller
- if exceptions already follow a module-specific pattern, do not introduce a generic runtime exception
- if tests in the area use a specific naming and structure style, follow that style

---

## Minimal-change rule

Prefer the smallest possible change that solves the requested problem completely.

That means:

- do not rename classes, methods, packages, or endpoints unless necessary
- do not rewrite unrelated code for style reasons
- do not expand scope “while you are there”
- do not replace existing working patterns just because another option exists
- do not perform opportunistic cleanup unless explicitly requested

If a task is narrow, keep the implementation narrow.

---

## Public contract and API consistency rules

Preserve existing contracts unless the task explicitly requires a contract change.

This includes:

- controller endpoint behavior
- request DTO structure
- response DTO structure
- service method contracts
- exception semantics
- error response format
- validation behavior where already established

### Important API constraints

- Keep endpoint naming and behavior consistent with the existing controllers.
- Maintain coherent naming for request and response DTOs.
- Avoid exposing entities directly from controllers unless that exact pattern already exists in that area.
- Do not silently change response shape.
- Do not silently change HTTP status behavior.
- Do not silently change validation semantics.

If a contract must change, do so intentionally and update affected tests.

---

## Exception handling rules

The project uses centralized exception handling through:

- `BusinessException`
- `ApiError`
- `GlobalExceptionHandler`

### Rules

- Reuse existing `BusinessException` patterns where appropriate.
- Prefer specific domain exceptions over generic `RuntimeException`.
- Keep error handling compatible with the current `ApiError` format.
- Do not swallow exceptions silently.
- Do not introduce inconsistent error responses.
- Keep error codes and HTTP status usage aligned with current patterns.
- Place new business exceptions in the appropriate module’s `exception` package unless they are truly shared.

If an existing module already has a clear exception style, follow it.

---

## Validation rules

Use validation deliberately and at the correct layer.

### Request validation
Use Bean Validation annotations in DTOs where possible.

Examples:
- `@NotBlank`
- `@NotNull`
- `@Email`
- `@Size`
- custom annotations only when built-in validation is not enough

### Business validation
Business rules belong in services and domain/entity logic where appropriate.

### Important validation constraints

- Keep request-shape validation in DTOs.
- Keep business rule validation out of controllers.
- Reuse existing custom validators instead of creating overlapping ones.
- Do not duplicate the same validation responsibility across multiple layers without reason.

---

## Security rules

Security is a critical part of this codebase.

The current design includes:

- JWT-based stateless authentication
- Spring Security configuration in `com.company.shop.config` and `com.company.shop.security`
- current-user access through `CurrentUserProvider` and related implementations
- centralized security constants and user-role handling

### Mandatory security rules

- Do not weaken authentication or authorization rules.
- Do not bypass security for convenience.
- Do not widen public endpoint exposure unless explicitly required.
- Reuse existing security components and patterns.
- Do not hardcode secrets, tokens, credentials, or API keys.
- Keep JWT-related changes consistent with the existing filter/provider flow.
- Respect current role and access patterns.
- Treat changes to filters, endpoint exposure, current-user resolution, JWT processing, and access rules as high risk.

### Security-sensitive areas to handle carefully

- `com.company.shop.security`
- `SecurityConfig`
- JWT provider/filter code
- current-user resolution
- admin/public endpoint access
- Stripe webhook exposure and verification

Any change in these areas must be minimal and carefully validated.

---

## Database and persistence rules

The project uses JPA/Hibernate and Flyway migrations.

Flyway migrations live under:

`src/main/resources/db/migration`

### Persistence rules

- Any schema change must be introduced as a new migration file.
- Do not edit old migrations unless explicitly requested.
- Preserve migration naming consistency.
- Keep JPA mappings aligned with the schema and current conventions.
- Avoid unnecessary eager fetching.
- Respect existing uniqueness constraints, check constraints, and locking behavior.
- Preserve consistency with optimistic locking where already used.
- Do not casually change entity relationships or cascade behavior.

### Database safety constraints

- Never modify historical migrations casually.
- Never introduce schema drift between entities and migrations.
- Never bypass important DB invariants in application code.
- Treat payment, order, cart, and stock-related persistence logic as sensitive.

---

## Transaction and consistency rules

When working on service logic:

- preserve existing transactional boundaries unless there is a clear reason to change them
- do not move business-critical write logic into controllers
- keep state-changing logic inside service layer orchestration
- be careful with race conditions, duplicate processing, stock updates, and payment/webhook flows

Especially validate changes involving:

- checkout
- payment processing
- webhook handling
- stock decrementing
- cart/order transitions
- uniqueness-sensitive flows

---

## Mapping rules

Reuse the project’s current mapper approach.

### Rules

- Keep mapping logic out of controllers where possible.
- Reuse existing mapper interfaces/classes.
- Keep mapping focused on transformation, not business rules.
- Do not duplicate mapping logic across services and controllers.
- If generated mappers are already used in an area, remain consistent with that pattern.

---

## Testing rules

Every non-trivial code change should include tests or updates to tests.

Prefer the narrowest useful test that proves the change.

### Preferred testing strategy

- unit tests for service logic and domain logic
- WebMvc tests for controller contracts and security behavior
- persistence/integration tests only when necessary
- migration/infrastructure verification only when relevant to the change

### Test style rules

- Follow the existing JUnit 5, AssertJ, Mockito, and Spring test style already used in the repository.
- Keep test names descriptive and aligned with current naming conventions.
- Reuse existing fixtures and support utilities where appropriate.
- Prefer realistic contract-focused tests over artificial ones.

### Important test constraints

- Do not rewrite unrelated tests when fixing a narrow issue.
- Do not broaden test scope without reason.
- Do not add speculative tests for behavior that is not part of the real contract.
- Do not create unnecessary base test classes, fixture frameworks, or builders unless duplication is real and repeated.
- When changing behavior, update or add the tests directly affected by that behavior.

Example naming style to preserve:

- `run_shouldThrowWhenRoleUserMissing`
- `run_shouldPassWhenRoleUserExists`
- `findById_shouldReturnUserWhenExists`
- `create_shouldThrowWhenEmailAlreadyExists`

---

## Documentation policy and code comments rules

Prefer self-explanatory code over verbose comments. The project documentation policy is defined in `docs/documentation-policy.md`; follow it for all documentation changes.

### API documentation source of truth

API documentation must be generated from the stable API behavior sources in the codebase:

- Spring MVC controller mappings
- request and response DTOs
- Bean Validation and custom validation annotations
- centralized exception and error contracts
- security configuration and method-level authorization
- OpenAPI annotations where present
- automated tests that verify API behavior

Generated API documentation is the source of truth for endpoint contracts. Contributors and Codex must not manually maintain endpoint lists, request/response tables, field catalogs, status-code matrices, or duplicated API contracts in Markdown unless explicitly requested for a specific task.

When adding or changing an endpoint, update the controller, DTOs, validation rules, exception/error behavior, security rules, OpenAPI annotations, and affected tests as needed so generated documentation remains accurate. Do not treat manual Markdown endpoint inventories as authoritative.

Generated documentation artifacts such as `openapi.json`, `openapi.yaml`, generated Swagger UI bundles, and `target/generated-docs` are build artifacts and must not be committed unless the project explicitly changes that policy in a later task.

### Manual documentation scope

Manual documentation under `docs/` should be limited to durable project knowledge that is not a duplicated endpoint contract, including:

- architecture and module responsibilities
- operational procedures
- setup and local development
- database migrations
- testing strategy
- ADRs and design decisions
- security model explanations

### Rules

- Do not add comments for obvious code.
- Add comments only when they provide real architectural or domain value.
- Keep documentation aligned with actual code behavior.
- Do not introduce placeholder documentation or speculative notes.
- If updating behavior that is already documented, keep the docs consistent without duplicating generated API contracts manually.

---

## Style and code quality rules

- Use constructor injection.
- Prefer `final` for dependencies.
- Keep methods focused and readable.
- Prefer explicit, descriptive names.
- Avoid overengineering.
- Avoid hidden side effects.
- Keep logic easy to review.

### Avoid

- generic utility dumping grounds
- unnecessary abstraction layers
- large inheritance hierarchies without need
- mixing infrastructure and business logic
- broad refactors
- stylistic rewrites unrelated to the task

---

## Files and directories to treat carefully

### High-risk source areas

- `src/main/resources/db/migration`
- `com.company.shop.security`
- `com.company.shop.config.SecurityConfig`
- `common.exception.GlobalExceptionHandler`
- `common.model` base entities
- order/payment/Stripe-related logic
- repository and persistence support code affecting DB invariants

### Never modify generated or build output

Do not modify files under:

- `target/`
- generated sources
- compiled classes
- surefire reports
- failsafe reports

Only change real source files and relevant top-level project files when necessary.

---

## Commands for verification

Use Maven directly unless the repository explicitly provides and uses a wrapper.

Typical verification commands include:

```bash
mvn test
mvn clean test
mvn -q -DskipTests compile
mvn clean package