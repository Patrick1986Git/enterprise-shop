# Documentation policy

This policy defines how project documentation is maintained and how API documentation is expected to evolve. It is the repository rule set for contributors and Codex sessions until a later task explicitly changes it.

## Current policy

### API documentation source of truth

API behavior must be described by the application code and verified behavior, not by manually maintained endpoint inventories. The stable source of API behavior is:

- Spring MVC controller mappings
- request and response DTOs
- Bean Validation and custom validation annotations
- centralized exception and error contracts
- security configuration and method-level authorization
- OpenAPI annotations where present
- automated tests that verify API behavior

Generated API documentation is the source of truth for endpoint contracts. Manual Markdown files must not duplicate endpoint lists, request/response tables, field catalogs, status-code matrices, or other API contract details unless a task explicitly requests a temporary or exceptional manual document.

When an endpoint is added or changed, contributors and Codex must update the relevant controllers, DTOs, validation annotations, exception/error contracts, security rules, OpenAPI annotations, and tests so future generated documentation remains correct.

### Generated documentation artifacts

Generated artifacts are build outputs for now and must not be committed unless a later project decision explicitly changes this rule. This includes, but is not limited to:

- `openapi.json`
- `openapi.yaml`
- generated Swagger UI bundles
- `target/generated-docs`
- other generated static API documentation outputs

The repository may expose generated documentation at runtime through SpringDoc/Swagger UI, and future automation may publish generated outputs as CI artifacts. Those generated files remain non-source artifacts under the current policy.

### Public Pages and complete CI artifact boundary

GitHub Pages is the project's intentionally narrow public documentation product surface. It may publish only the generated `public-api` group, which is intended for unauthenticated external consumers. The aggregate OpenAPI document and the customer, ADMIN, webhook, and system groups remain in the complete, non-Pages `openapi-docs` CI artifact for build validation and review.

Because this repository is public, neither the CI artifact nor the source code is a confidentiality or authorization boundary, and ADMIN endpoint existence is not secret. Limiting Pages reduces direct publication through stable URLs and machine-readable discoverability; runtime authentication and authorization remain the protection for privileged operations.

The Pages bundle is generated fail closed: its file set and index links are restricted to the approved public group, and semantic validation must reject ADMIN, actuator, customer-only, and webhook paths even if they are introduced under an unexpected filename. New or reclassified OpenAPI groups must not be added to Pages until this policy explicitly identifies them as public and regression coverage proves the resulting contract is public-safe.

Both the public Pages specifications and the complete CI specifications remain uncommitted build outputs.

## Manual documentation scope

Manual documentation under `docs/` should capture durable knowledge that is not safely or usefully generated from code. Appropriate manual documentation includes:

- architecture and cross-cutting design
- module responsibilities and boundaries
- operational procedures and runbooks
- setup and local development workflows
- database migration strategy and conventions
- testing strategy and quality gates
- ADRs and design decisions
- security model explanations

Manual documents may link to generated API documentation locations or explain how generated documentation is produced, but they should not restate generated endpoint contracts.

## What must not be duplicated manually

Do not manually maintain Markdown copies of:

- complete endpoint inventories
- request and response schemas
- DTO field tables
- validation constraint tables
- authentication and authorization matrices for every endpoint
- status-code matrices for every endpoint
- example payload catalogs that are intended to be contract references

If this information is needed, it should come from generated OpenAPI documentation, tests, or focused examples in executable or generated form.

## Guidance for future Codex API changes

For future tasks that add or modify API behavior, Codex should:

1. Update the code-level API contract first: controllers, DTOs, validation annotations, security rules, error handling, and OpenAPI annotations where applicable.
2. Add or update automated tests that prove the contract and protect generated documentation accuracy.
3. Avoid adding manual endpoint tables or duplicated request/response documentation to Markdown.
4. Update manual docs only when the change affects architecture, module responsibilities, operations, setup, migrations, testing strategy, security model explanations, or recorded design decisions.
5. Leave generated OpenAPI/static documentation artifacts uncommitted unless the task explicitly changes the repository policy.

## Future direction

The intended documentation automation direction is:

- generate OpenAPI documentation from application code and tests
- make generated documentation available as build or CI artifacts
- validate documentation completeness in CI
- optionally publish generated documentation through GitHub Pages or another static documentation site

These items describe planned direction only. This policy document does not implement OpenAPI generation changes, new annotations, CI publishing, documentation tests, or static site publication.
