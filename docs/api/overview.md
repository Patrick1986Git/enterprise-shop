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
