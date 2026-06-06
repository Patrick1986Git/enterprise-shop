# Security architecture

## Authentication model

- The application uses stateless JWT authentication.
- Sessions are configured with `SessionCreationPolicy.STATELESS`.
- Form login and HTTP Basic are disabled.
- `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`.
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

Current CORS configuration:

| Setting | Value |
| --- | --- |
| Allowed origins | `http://localhost:3000`, `http://localhost:8080` |
| Allowed methods | `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS` |
| Allowed headers | `Authorization`, `Cache-Control`, `Content-Type`, `X-Request-Id` |
| Exposed headers | `Authorization`, `X-Request-Id` |
| Credentials | Allowed |

## Security headers

The filter chain sets a referrer policy of `STRICT_ORIGIN_WHEN_CROSS_ORIGIN`.
