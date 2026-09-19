# ArchUnit quality gate

## What is ArchUnit

ArchUnit is a testing library for defining and executing architectural rules directly against Java code. The rules run as standard JUnit 5 tests.

## Why we use it in this project

This is the first lightweight quality gate for our modular monolith. The goal is to quickly detect layering and dependency violations between core package roles (controller/service/repository/dto/entity) without a large refactor or package redesign.

## Currently enforced rules

`ArchitectureRulesTest` imports classes from `com.company.shop` and enforces:

1. `..controller..` must not depend on `..repository..`.
2. `..repository..` must not depend on `..controller..`.
3. `..repository..` must not depend on `..service..`.
4. `..service..` must not depend on `..controller..`.
5. `..dto..` must not depend on non-enum classes from `..entity..` packages (enum types from entity packages are currently allowed).
6. `..entity..` must not depend on `..dto..`, `..controller..`, `..service..`, `..repository..`.
7. `*Controller` classes in `..controller..` must be annotated with `@RestController`.
8. Types in `..repository..` whose names end with `Repository` must be interfaces.
9. A business module (`cart`, `category`, `notification`, `order`, `product`, `system`, or `user`) must not depend
   directly on a different business module's `repository` or `service` package. Same-module access remains allowed;
   cross-module orchestration must use the documented narrow `api.internal` contracts or the documented order outbox
   handler/event contract. The rule reports both the source and forbidden target so the owning boundary is clear.
10. Order must not depend on cart, product, or user entities. These additional rules preserve the checkout snapshot
    boundary and are intentionally stricter than the general repository/service ownership rule.

The business-module rule deliberately does not prohibit all cross-module dependencies. The relational model retains
the documented `Cart -> User`, `CartItem -> Product`, `Product -> Category`, and `ProductReview -> User` associations.
The narrow category and user association facades may therefore expose a managed entity where that existing JPA
relationship requires one. Immutable records remain the preferred internal API payload everywhere else. The
notification module consumes order-owned outbox types, and security/authentication's cross-cutting access to User
authentication persistence is outside the business-module selector.

## How to run

Run only ArchUnit tests:

```bash
mvn -Dtest=ArchitectureRulesTest test
```

Run the full test suite:

```bash
mvn test
```

## How to add new rules carefully

- Start with low-risk, highly readable rules.
- Prefer rules aligned with the current architecture over rules that force large restructuring.
- If the current codebase violates an otherwise valid rule, mark that rule as `@Disabled` temporarily with a clear `TODO` and a remediation plan.
- Do not replace the ownership rule with blanket module isolation: intentional entity associations and the outbox
  handler contract are part of the documented monolith architecture.
- Add a package to the centralized business-module set when introducing a new business module so repository and service
  ownership is enforced in both directions.
