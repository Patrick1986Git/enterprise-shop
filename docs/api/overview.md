# API documentation overview

API contract documentation is generated from the Spring MVC controllers, DTOs, validation annotations, exception/error contracts, security configuration, OpenAPI annotations, and automated tests. Generated API documentation is the source of truth for endpoint contracts.

This repository intentionally does not maintain a manual Markdown endpoint inventory, request/response table set, or duplicated API contract reference. See the project documentation policy for the full source-of-truth rules and future automation direction:

- [`../documentation-policy.md`](../documentation-policy.md)

## Runtime documentation entry points

When the application is running locally, SpringDoc exposes API documentation through the configured OpenAPI and Swagger UI endpoints:

- OpenAPI JSON: `http://localhost:8080/api-docs`
- Grouped OpenAPI JSON: `http://localhost:8080/api-docs/{group}` for stable SpringDoc groups such as `all-api`, `public-api`, `customer-api`, `admin-api`, `webhooks-api`, and `system-api`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`

These runtime endpoints are generated from the application. Generated files such as `openapi.json`, `openapi.yaml`, Swagger UI bundles, and `target/generated-docs` are build artifacts and must not be committed under the current policy.

## CI-generated OpenAPI artifacts

CI generates downloadable OpenAPI JSON and YAML files from the same SpringDoc runtime contracts during the Maven build. The generated files are published as the `openapi-docs` GitHub Actions artifact and include the default OpenAPI document plus the configured SpringDoc groups.

CI also packages a lightweight static documentation site as the `api-docs-site` GitHub Actions artifact. The site contains an `index.html` page with links to the default and grouped OpenAPI JSON/YAML files so the generated API contracts can be browsed from a downloaded artifact or published later through GitHub Pages or another static host. This repository does not enable static site deployment from this artifact by default.

Generated OpenAPI files are written under `target/generated-docs/openapi/`, and the static site is written under `target/generated-docs/site/` during the build. These directories are build output only and must not be committed.
