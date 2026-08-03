# Testing strategy

## Tooling

- Java 21.
- Maven Wrapper is present and should be used for repeatable local/CI commands.
- CI runs `./mvnw -B clean verify`.
- Test stack includes JUnit 5, AssertJ, Mockito, Spring MVC test support, Spring Security test support, Testcontainers PostgreSQL, Flyway test support, and ArchUnit. Persistence integration tests start dynamically managed Testcontainers PostgreSQL instances and do not depend on the Docker Compose database. They remain independent of the local Compose least-privilege runtime role bootstrap.

Mockito static mocking is used in service tests, including Stripe SDK entry points. The build config starts tests with Mockito as an explicit `-javaagent` to avoid JDK 21+ dynamic self-attach warnings and keep execution compatible with stricter future JDK defaults.

## Coverage reporting

JaCoCo measures which production bytecode instructions, lines, and branches execute during the Maven test lifecycle. Both the Surefire unit/service/WebMvc/repository test phase and the Failsafe `*IT.java` integration-test phase, including Testcontainers PostgreSQL paths, contribute to one `target/jacoco.exec` execution-data file. The agent uses append mode, so a later fork or test phase adds its probes instead of replacing coverage recorded by the first phase. No project-specific coverage exclusions are configured.

JaCoCo's `prepare-agent` goal populates the existing `argLine` property. Surefire and Failsafe resolve that value with Maven's late `@{argLine}` syntax and then add the existing explicit Mockito `-javaagent`; consequently both agents are present without replacing or duplicating the Mockito agent.

Run the complete suite and validate the report locally with:

```bash
./mvnw -B clean verify
python scripts/validate-jacoco-report.py
```

The Maven `verify` lifecycle generates the reviewable HTML report at `target/site/jacoco/index.html`, machine-readable XML at `target/site/jacoco/jacoco.xml`, and CSV at `target/site/jacoco/jacoco.csv`. CI validates their identity and structure, prints deterministic line and branch totals to the workflow log and job summary, and publishes only these three files as the `jacoco-coverage-report` artifact for 14 days.

This initial measurement intentionally has no blocking percentage threshold: the GitHub-hosted run establishes evidence for a measured baseline rather than introducing an arbitrary target. Coverage shows which code executed, but does not prove assertion quality, behavior correctness, or adequate edge-case testing. A focused follow-up can use the confirmed line and branch counts, normal run-to-run variability, and reviewable tolerance to define a modest non-regression gate.

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

## CodeQL source analysis

The dedicated `CodeQL` workflow analyzes the repository's Java/Kotlin production source separately from application CI and container supply-chain validation. It runs for pull requests targeting `master`, pushes to `master`, manual dispatches, and every Wednesday at `03:37 UTC` (`37 3 * * 3`). Per-PR/ref concurrency cancels only a superseded analysis of the same change. The workflow initializes CodeQL in manual build mode, compiles and tests with Temurin 21 and `./mvnw -B clean verify`, and uses the official `security-extended` Java query suite for security-relevant data-flow and code-quality findings.

The workflow defaults to `contents: read`; only its analysis job adds `security-events: write` for the code-scanning upload. It uses the ordinary `pull_request` event, including for fork contributions, so untrusted changes never run in the privileged `pull_request_target` context and receive no repository secrets or write-capable checkout credentials. GitHub downgrades write permissions for fork pull requests while supporting CodeQL result processing for the pull-request event.

CodeQL action releases are full-SHA pinned with adjacent exact-version comments. Maintain them through the existing weekly GitHub Actions Dependabot configuration and review each proposed official tag-to-commit mapping; updates are not auto-merged. Findings are available under the repository's **Security > Code scanning** view and as pull-request annotations when GitHub can associate a result with changed code.

CodeQL examines source-level data flow and code patterns. Maven tests verify application behavior, Trivy reports vulnerabilities in the built application and PostgreSQL container contents, and `govulncheck` verifies the narrowly reviewed gosu source exception. None substitutes for another. Run the local build inputs with:

```bash
./mvnw -B validate
./mvnw -B clean verify
```

The official CodeQL database extraction, query execution, GitHub code-scanning history, pull-request annotations, and SARIF upload require the GitHub-hosted workflow and cannot be reproduced fully by these Maven commands alone.

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
