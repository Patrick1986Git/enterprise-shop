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

CI also packages a lightweight public documentation site as the `api-docs-site` GitHub Actions artifact. The site contains an `index.html` page linking only to the public API JSON/YAML files. The aggregate document and the customer, ADMIN, webhook, and system groups remain available to maintainers in the separate `openapi-docs` artifact. Pull request builds are validation-only: they run the documentation generation checks and upload both artifacts for review, but they do not publish GitHub Pages.

Pushes to `master` publish the public-only static site from `target/generated-docs/site/` to GitHub Pages through the repository's GitHub Actions Pages workflow, if GitHub Pages is enabled and configured to use GitHub Actions in the repository settings. Semantic generation tests enforce that this public bundle contains no ADMIN, actuator, customer-only, or webhook paths. The workflow uses the `github-pages` deployment environment and does not require secrets, a custom domain, or a generated documentation branch.

Generated OpenAPI files are written under `target/generated-docs/openapi/`, and the static site is written under `target/generated-docs/site/` during the build. These directories are build output only and must not be committed.
