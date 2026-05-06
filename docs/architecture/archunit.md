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
- Avoid aggressive cross-module isolation rules at the beginning; introduce them incrementally after dependency analysis.
