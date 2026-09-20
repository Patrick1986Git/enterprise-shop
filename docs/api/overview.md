# API documentation overview

API contract documentation is generated from the Spring MVC controllers, DTOs, validation annotations, exception/error contracts, security configuration, OpenAPI annotations, and automated tests. Generated API documentation is the source of truth for endpoint contracts.

This repository intentionally does not maintain a manual Markdown endpoint inventory, request/response table set, or duplicated API contract reference. See the project documentation policy for the full source-of-truth rules and future automation direction:

- [`../documentation-policy.md`](../documentation-policy.md)

## Runtime documentation entry points

When the application is running locally, SpringDoc exposes API documentation through the configured OpenAPI and Swagger UI endpoints:

- OpenAPI JSON: `http://localhost:8080/api-docs`
- Grouped OpenAPI JSON: `http://localhost:8080/api-docs/{group}` for stable SpringDoc groups such as `all-api`, `public-api`, `customer-api`, `admin-api`, `webhooks-api`, and `system-api`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`

The `prod` profile disables the runtime OpenAPI endpoints and Swagger UI; local development and CI documentation generation remain enabled. These runtime endpoints are generated from the application. Generated files such as `openapi.json`, `openapi.yaml`, Swagger UI bundles, and `target/generated-docs` are build artifacts and must not be committed under the current policy.

## CI-generated OpenAPI artifacts

CI generates downloadable OpenAPI JSON and YAML files from the same SpringDoc runtime contracts during the Maven build. The generated files are published as the `openapi-docs` GitHub Actions artifact and include the default OpenAPI document plus the configured SpringDoc groups.

CI also packages a lightweight public documentation site as the `api-docs-site` GitHub Actions artifact. The site contains an `index.html` page linking only to the public API JSON/YAML files. The separate `openapi-docs` build/review artifact retains the aggregate document and the customer, ADMIN, webhook, and system groups. In this public repository, that artifact and the source code are not confidentiality boundaries; restricting Pages reduces direct publication and machine-readable discoverability rather than making privileged routes secret. Runtime authentication and authorization remain the protection for privileged operations. Pull request builds are validation-only: they run the documentation generation checks and upload both artifacts for review, but they do not publish GitHub Pages.

Pushes to `master` publish the public-only static site from `target/generated-docs/site/` to GitHub Pages through the repository's GitHub Actions Pages workflow, if GitHub Pages is enabled and configured to use GitHub Actions in the repository settings. Semantic generation tests enforce that this public bundle contains no ADMIN, actuator, customer-only, or webhook paths. The workflow uses the `github-pages` deployment environment and does not require secrets, a custom domain, or a generated documentation branch.

Generated OpenAPI files are written under `target/generated-docs/openapi/`, and the static site is written under `target/generated-docs/site/` during the build. These directories are build output only and must not be committed.

## Backward-compatibility gate

Pull requests compare all seven JSON contracts (`openapi`, `all-api`, `public-api`, `customer-api`, `admin-api`, `webhooks-api`, and `system-api`) with documents generated independently from the pull request's exact protected-base SHA. The aggregate document protects the complete API, while the audience documents also detect accidental movement between consumer groups. Duplicate findings are consolidated and list every affected document. YAML remains a generated review format, but JSON is the canonical machine-comparison format because each pair is produced from the same runtime model.

The baseline is not committed and is not downloaded from a retained artifact. CI checks out `github.event.pull_request.base.repo.full_name` at `github.event.pull_request.base.sha` into an isolated directory with persisted credentials disabled, verifies the checkout SHA and repository identity, runs that revision's `OpenApiDocsSmokeTest`, and writes an exact repository/SHA provenance manifest. Missing, malformed, incomplete, externally referenced, or incorrectly provenanced baseline input fails the comparison closed. This avoids generated-source duplication and artifact-retention or moving-branch races. It costs one focused Spring test execution per pull request; unlike running the candidate's generator against old source, it uses the trusted base revision's own pinned Maven build definition.

The repository-owned `scripts/openapi_compatibility.py` checker uses only the Python standard library and understands OpenAPI 3.x structure and local component references; it is not a textual JSON diff and adds no application dependency or external CI action. It rejects removed paths/operations/parameters/responses/media types/properties, operation ID changes, required request bodies/properties/parameters, request constraint tightening (including enum narrowing, bounds, patterns, type/format, and nullability), incompatible response schema changes, and every documented security-requirement change. Security detection is deliberately symmetric: both adding and removing requirements require review even when one direction is security-positive. Additive endpoints, optional request properties, and response properties are allowed. Existing semantic tests remain responsible for project-specific meaning that a structural checker cannot infer.

There is intentionally no wildcard, label, commit-message, or permanent allowlist bypass. The repository does not yet define who may approve a deliberate breaking change or a major-version policy. When a break is intentional, the gate reports the exact change and the owner must establish a separately reviewed, narrowly scoped approval policy before changing the detector; contributors must not disable the check or weaken the production contract to make it pass.

To reproduce locally, generate candidate documents with `./mvnw -B -Dtest=OpenApiDocsSmokeTest test`, generate the base revision's documents in a separate worktree with the same focused command, place `{"repository":"Patrick1986Git/enterprise-shop","sha":"<base-sha>"}` in its OpenAPI output as `baseline-provenance.json`, then run:

```bash
python scripts/openapi_compatibility.py \
  --baseline <base-worktree>/target/generated-docs/openapi \
  --candidate target/generated-docs/openapi \
  --baseline-sha <base-sha>
```

Exit code `1` means a detected incompatible contract change; exit code `2` means baseline/candidate provenance or input could not be established and is also a CI failure. Review the consolidated finding and its audience-document list rather than treating arbitrary generated-text changes as breaking.
