# Security architecture

## Authentication model

- The application uses stateless JWT authentication.
- Sessions are configured with `SessionCreationPolicy.STATELESS`.
- Form login and HTTP Basic are disabled.
- `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`.
- A JWT's signature and expiration establish token authenticity, but do not by themselves establish current account authorization. On every bearer-authenticated request, the filter reloads the active user and current roles from PostgreSQL. Missing, soft-deleted, disabled, expired, or locked accounts remain unauthenticated.
- Account deletion, disablement, and persisted role changes therefore take effect on the next request. Authorities in the JWT are issuance-time metadata only; request authorization uses the authoritative roles loaded from persistence.
- Password hashing uses `BCryptPasswordEncoder`.
- Method security is enabled and controllers use `@PreAuthorize` for authenticated/admin boundaries.

## Public endpoints

`SecurityConstants.PUBLIC_ENDPOINTS` permits:

- `/api/v1`
- `/api/v1/auth/**`
- `/css/**`, `/js/**`, `/images/**`
- `/swagger-ui/**`
- `/api-docs`, `/api-docs/**`
- `/v3/api-docs/**`

These documentation matchers support local development and documentation generation. The `prod` profile disables both SpringDoc API documents and Swagger UI, so they do not create production runtime handlers.

`SecurityConfig` also permits public catalog/review reads:

- `GET /api/v1/products`
- `GET /api/v1/products/search`
- `GET /api/v1/products/slug/**`
- `GET /api/v1/products/category/**`
- `GET /api/v1/products/*/reviews`
- `GET /api/v1/categories`
- `GET /api/v1/categories/slug/**`

## Protected routes

- `/api/v1/admin/**` requires `ROLE_ADMIN` at the filter-chain level and admin controllers also use `@PreAuthorize("hasRole('ADMIN')")`.
- Other non-public routes require authentication.
- Current-user controllers use `@PreAuthorize("isAuthenticated()")`.

## Webhooks and CSRF

- CSRF is ignored only for `/api/v1/webhooks/**`.
- `/api/v1/webhooks/**` is public at the HTTP authorization layer so Stripe can call it.
- Stripe webhook authenticity is enforced in `PaymentServiceImpl` through Stripe signature construction/verification using the configured webhook secret.

## Actuator access

| Endpoint | Access |
| --- | --- |
| `/actuator/health` | Public |
| `/actuator/info` | Admin |
| `/actuator/metrics` | Admin |
| `/actuator/prometheus` | Admin |

## CORS

Credentialed browser CORS uses an explicit origin allowlist:

| Setting | Value |
| --- | --- |
| Allowed origins | Default/development: `http://localhost:3000`, `http://localhost:8080`; production: `CORS_ALLOWED_ORIGINS` |
| Allowed methods | `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS` |
| Allowed headers | `Authorization`, `Cache-Control`, `Content-Type`, `X-Request-Id`, `Idempotency-Key` |
| Exposed headers | `Authorization`, `X-Request-Id` |
| Credentials | Allowed |

`CORS_ALLOWED_ORIGINS` is an optional comma-separated list of explicit HTTP(S) origins, for example
`https://shop.example,https://admin.example`. Production has an empty allowlist when the variable is absent, so
same-origin and server-to-server traffic remain available while cross-origin browser access fails closed. Wildcards,
paths, queries, fragments, and non-HTTP(S) values are rejected; localhost is trusted in production only when listed
explicitly.

## Security headers

The filter chain sets a referrer policy of `STRICT_ORIGIN_WHEN_CROSS_ORIGIN`.

## Authorization freshness decision

Immediate account and authority revocation is required because an administrator can soft-delete an account while an issued access token is still valid. Reloading `UserDetails` for each authenticated request is the narrowest design compatible with the existing repository query, disabled-account checks, stateless HTTP sessions, and lazy role mapping. The lookup runs in the existing read-only service transaction, fetches roles explicitly, and does not depend on Open Session in View.

Alternatives were rejected for this lifecycle:

- A persisted security or authorization version would still require an authoritative lookup to validate the version, while adding a schema migration and concurrent increment semantics without reducing database reads.
- A dedicated revocation or session store would add operational state and infrastructure for behavior already represented by the users and roles tables.
- Allowing stale authorization until the one-hour access-token expiration would permit deleted administrators to retain privileges and does not satisfy immediate revocation.

HTTP sessions remain stateless: the database lookup validates each independent bearer request and does not create a server-side login session. The configured `refresh-expiration` property is not used by a refresh-token issuance or exchange flow.

## Account and token lifecycle

Login normalizes the submitted email and delegates password, enabled-state, and active-account checks to Spring Security backed by `UserDetailsServiceImpl`. Successful authentication produces an HMAC-signed bearer JWT whose subject is the normalized email, whose `roles` claim records the issuance-time authorities, and whose configured lifetime is one hour. There is no refresh-token endpoint, persisted token/session record, revocation list, authorization version, or security-stamp mechanism.

`JWT_SECRET` has one representation in every profile: standard RFC 4648 Base64 encoding of the signing-key bytes. Production key material must be generated from at least 32 cryptographically random bytes (for example, `openssl rand -base64 32`); Base64 is only an encoding and does not encrypt the key. Startup fails before traffic is served when the value is missing, blank, malformed, decodes to fewer than 256 bits, or when the access-token lifetime is non-positive or cannot be added safely to the current epoch time. Diagnostics identify the invalid property without including its value.

JJWT chooses the strongest compatible HMAC algorithm for the decoded key: a 256-bit key produces HS256, while larger keys can produce HS384 or HS512. This preserves the library's existing safe algorithm selection rather than imposing a narrower algorithm contract. Changing or rotating the decoded signing-key bytes invalidates all outstanding access tokens. The repository has no old-key verification, key identifier, or refresh-token exchange mechanism, so a rollout that changes from the former Base64-text bytes behavior to decoded bytes requires a planned one-time logout; the documented one-hour access-token lifetime bounds the normal disruption. This repository contains no production deployment definition or evidence from which existing-token preservation can be guaranteed.

The current production user-management API can update profile names and soft-delete users. Registration assigns `ROLE_USER`; there is no production endpoint or service operation that disables a user or adds/removes roles, and no production caller invokes `User.disable()`. Direct administrative persistence changes to enabled state or role membership are nevertheless enforced on the next authenticated request by the same authoritative reload.
