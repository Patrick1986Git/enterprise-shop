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
11. Every production class below `com.company.shop.module.<name>` must belong to the explicit business-module registry.
    This fails closed: introducing a direct child package without intentionally registering and reviewing it reports the
    class and unregistered module name.
12. Cross-module use of `api.internal` is limited to the documented owner/consumer matrix below. Same-owner use remains
    unrestricted, while an undocumented cross-module dependency reports both modules and types.
13. Notification may depend on order only through `com.company.shop.module.order.outbox`; order repositories, services,
    entities, and other implementation packages are forbidden.

The business-module rule deliberately does not prohibit all cross-module dependencies. The relational model retains
the documented `Cart -> User`, `CartItem -> Product`, `Product -> Category`, and `ProductReview -> User` associations.
The narrow category and user association facades may therefore expose a managed entity where that existing JPA
relationship requires one. Immutable records remain the preferred internal API payload everywhere else. The
notification module consumes order-owned outbox types, and security/authentication's cross-cutting access to User
authentication persistence is outside the business-module selector.

## Internal API owner/consumer matrix

| Owner | Internal API types | Allowed cross-module consumers | Reason and value boundary |
| --- | --- | --- | --- |
| cart | `CartCheckoutFacade`, `CartCheckoutSnapshot`, `CartCheckoutItem` | order | Checkout reads and reconciles a cart through immutable record values rather than cart entities. |
| category | `ProductCategoryFacade`, `CategoryProductRetirementPolicy` | product | Product resolves the managed `Category` required by its existing JPA association and implements the category-owned retirement policy port. |
| product | `ProductCatalogFacade`, `CheckoutProduct`, `ReservedInventoryItem` | cart, order | Cart alone resolves the managed `Product` required by its existing association; checkout and compensation exchange immutable record values. |
| user | `CurrentUserFacade`, `CurrentUserSnapshot`, `CurrentUserAssociationFacade` | cart, order, product | General identity access uses an immutable snapshot. Cart and product alone may resolve a managed `User` for their existing associations; order uses only the snapshot facade. |

These are the complete production `api.internal` packages at this boundary. `CurrentUserAssociationFacade` is not a
general user repository proxy: only cart creation and product-review association code may consume it. Similarly,
`ProductCategoryFacade` and the association method on `ProductCatalogFacade` exist only for established JPA
relationships. New consumers require an intentional matrix change and architecture review.

Notification is outside this internal-API matrix because its event boundary is order-owned
`com.company.shop.module.order.outbox`. The dedicated rule permits that package and rejects every other direct
notification-to-order dependency.

## How to run

Run only ArchUnit tests:

```bash
./mvnw -B -Dtest=ArchitectureRulesTest,ArchitectureRuleRegressionTest test
```

Run the full test suite:

```bash
./mvnw -B test
```

## How to add new rules carefully

- Start with low-risk, highly readable rules.
- Prefer rules aligned with the current architecture over rules that force large restructuring.
- If the current codebase violates an otherwise valid rule, mark that rule as `@Disabled` temporarily with a clear `TODO` and a remediation plan.
- Do not replace the ownership rule with blanket module isolation: intentional entity associations and the outbox
  handler contract are part of the documented monolith architecture.
- Add a package to the centralized business-module set when introducing a new business module so repository/service
  ownership and internal-API directionality are enforced in both directions. The registry consistency rule rejects the
  new package until this intentional update is made.
- Update the internal API owner/consumer matrix in both the test and this document when a reviewed cross-module port is
  introduced. Do not grant broad module access merely because one type needs a new consumer.
