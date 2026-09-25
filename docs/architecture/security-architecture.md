# Security architecture

## Authentication model

- The application uses stateless JWT authentication.
- Sessions are configured with `SessionCreationPolicy.STATELESS`.
- Form login and HTTP Basic are disabled.
- `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`.
- A JWT's signature and expiration establish token authenticity, but do not by themselves establish current account authorization. On every bearer-authenticated request, the filter reloads the active user and current roles from PostgreSQL. Missing, soft-deleted, disabled, expired, or locked accounts remain unauthenticated.
- Account deletion, disablement, and persisted role changes therefore take effect on the next request. Authorities in the JWT are issuance-time metadata only; request authorization uses the authoritative roles loaded from persistence.
- Password hashing uses `BCryptPasswordEncoder`. Registration and authenticated password change enforce the same
  8-to-72-character rule and the BCrypt-specific 72-byte UTF-8 ceiling.
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

HTTP sessions remain stateless: the database lookup validates each independent bearer request and does not create a server-side login session. Enterprise Shop issues access tokens only. It has no refresh-token exchange, server-side session store, or refresh-token persistence. Adding refresh tokens in the future would require a separately reviewed security and session-lifecycle design.

## Account and token lifecycle

Login normalizes the submitted email and delegates password, enabled-state, and active-account checks to Spring Security backed by `UserDetailsServiceImpl`. Successful authentication produces an HMAC-signed bearer JWT whose subject is the normalized email, whose `roles` claim records the complete issuance-time authority set from Spring Security, and whose production lifetime is the repository-owned fixed policy of exactly one hour (`3600000` milliseconds). A production-only startup validator rejects any effective property-source override away from that policy. Changing the lifetime is a security and deployment decision, not performance tuning, because previous-key retirement must wait until every dependent token has expired. Despite its historical name, that claim is authority metadata and is not limited to domain `ROLE_*` values. There is no refresh-token endpoint, persisted token/session record, or revocation list. Each User has a monotonically increasing `credential_version`; login binds that value to the JWT `credentialVersion` claim and every bearer request compares it with the current persisted value. Missing, malformed, negative, or stale claim values fail closed. Tokens issued before this contract do not have the claim and become unauthenticated at rollout; this deliberate one-time cutover is bounded by deployment rather than creating an indefinite legacy-token format.

## Credential lifecycle and password management

Public registration and login are the only unauthenticated credential operations. An authenticated User may change only
their own password with `PUT /api/v1/me/password`. The operation takes the same PostgreSQL transaction-scoped advisory
User lifecycle lock as profile updates and retirement, reloads the active User after acquiring the lock, verifies the
current password with the repository-owned BCrypt encoder, hashes the validated replacement, and increments
`credential_version` atomically with the password update. It does not change email or roles. Consequently, retirement
that wins the lock prevents the password mutation, while a password change that wins commits fully before retirement;
neither ordering can resurrect an account.

Existing JWTs must stop authenticating immediately after a password change. Persisted credential versioning is selected
because the filter already performs the authoritative User lookup, so comparison adds no session or revocation store and
avoids timestamp precision, clock, transaction-ordering, and migration-backfill ambiguity. Allowing access until the
one-hour expiry was rejected because repository evidence does not authorize that post-credential-change exposure. A
password-change timestamp compared to `iat` was rejected for its database/JWT precision and commit-order races. A
token/session revocation store was rejected as unnecessary operational state for this architecture.

Forgotten-password recovery is intentionally not implemented. The
[recovery boundary](./password-recovery-boundary.md) records the threat model and audit of opaque-token, encrypted-payload,
after-commit SMTP, and provider-template alternatives. The repository defines neither a secure raw-token delivery and retry
contract nor an approved expiry/retention policy, so no reset-token issuance, persistence, endpoint, or consumption path may
be added until the documented owner inputs are resolved.

`JWT_SECRET` has one representation in every profile: standard RFC 4648 Base64 encoding of the signing-key bytes. Production key material must be generated from at least 32 cryptographically random bytes (for example, `openssl rand -base64 32`); Base64 is only an encoding and does not encrypt the key. Startup fails before traffic is served when the value is missing, blank, malformed, decodes to fewer than 256 bits, or when the access-token lifetime is non-positive or cannot be added safely to the current epoch time. Diagnostics identify the invalid property without including its value.

JJWT chooses the strongest compatible HMAC algorithm for the decoded key: a 256-bit key produces HS256, while larger keys can produce HS384 or HS512. This preserves the library's existing safe algorithm selection rather than imposing a narrower algorithm contract. New tokens include a configured non-secret `kid` and are signed only by the active key. The parser uses JJWT's supported key-locator API to select the verification key from the protected header before parsing claims or verifying the signature. Production may optionally configure exactly one previous verification key for a bounded rollover window; it never signs new tokens. Unknown key IDs fail authentication. During the first rollout only, legacy no-`kid` tokens are accepted with the unchanged active key while no previous key is configured; configuring a previous key disables that fallback. Planned rotation, retirement, rollback, and emergency-compromise procedures are documented in `docs/operations/jwt-key-rotation.md`. There is still no refresh-token exchange or server-side session mechanism.

The administrative User API lists and reads active users, updates profile names, permanently retires accounts, and exposes
narrow disable and enable commands. Disablement is a temporary authentication suspension: it retains the User, email,
roles, and related commerce data. The disable transition increments `credential_version` in the same transaction before
setting `enabled=false`. Re-enablement restores login and bearer authentication but does not increment the version again.
Consequently, a token issued before suspension fails while the account is disabled and remains stale after re-enablement;
only a subsequent login can issue a token for the new version. Repeated disable or enable commands are idempotent and do
not repeatedly advance the version. Without the disable-time increment, the per-request enabled check would revoke the
token only temporarily and that token could resurrect after re-enablement.

Account-state commands use the existing per-User PostgreSQL transaction-scoped lifecycle advisory lock and repeat the
active lookup after acquiring it. They therefore serialize with profile updates, password changes, commerce operations,
retirement, and each other. Retirement remains permanent: it wins against a waiting state command by making the repeated
active lookup fail, and no enable command can find or restore the soft-deleted User. No schema change is needed because
`enabled` and `credential_version` are existing persisted invariants.

The API deliberately does not add role commands. Registration assigns only `ROLE_USER`; the schema seeds `ROLE_USER` and
`ROLE_ADMIN`, but the repository has no production administrator bootstrap, owner role, self-demotion policy, or stated
last-active-admin invariant. An unrestricted role patch would therefore invent policy for self-demotion, final-admin
demotion, and concurrent changes. Current persisted roles remain authoritative on every request, so an out-of-band
demotion immediately removes ADMIN access and an out-of-band promotion immediately grants it even when the JWT role
metadata differs. Production role management is deferred until an owner defines bootstrap, assignable roles,
self-action, final-active-admin, and concurrency policy; any selected final-admin invariant must be PostgreSQL-backed,
not an in-memory count.

The complete administrator bootstrap, continuity, zero-administrator recovery, database-identity, concurrency, and audit
analysis is recorded in the [administrator lifecycle boundary](./administrator-lifecycle-boundary.md). It deliberately
fails closed pending named owner decisions; local PostgreSQL role bootstrap tooling is not an application administrator
bootstrap mechanism.

The existing admin profile-update and retirement APIs permit self-action and do not protect a final active administrator.
The new state commands preserve that established behavior rather than silently imposing a different policy: an
administrator may disable their own account or another administrator, including the final active administrator, and the
change takes effect on the next bearer request. This may require database/operator recovery, so a prohibition or
last-admin invariant remains an explicit product-owner decision. Role self-demotion is unsupported because all role
mutation is unsupported.

No User-administration action-log table is added. Existing append-only admin action logs are bounded to operational
requeue/recovery workflows and record committed successful commands; they do not establish a repository-wide identity
administration audit contract or durable rejected-outcome transaction. Defining actor retention, target snapshots,
success/failure outcomes, read authorization, and transactional treatment of rejected attempts is an owner decision.
Application logs must not be treated as a substitute and must never contain passwords, JWTs, request bodies, or raw
credentials.

The alternatives considered were: disable without enable, rejected because the existing persisted `enabled` state and
domain disable operation describe a reversible suspension more narrowly than permanent retirement; retaining retirement
as the only revocation mechanism, rejected because it cannot represent temporary suspension and permanently hides the
identity; and disable plus re-enable without version invalidation, rejected because it resurrects pre-suspension JWTs.
Disable plus re-enable with disable-time credential-version invalidation is the selected minimum coherent lifecycle.

A normalized email remains reserved after User retirement. This is an authentication-identity and token-replay safety
invariant, not merely an incidental uniqueness constraint: JWT subjects contain email, and each request binds that subject
to the currently active account and its current authorities. Allowing a new account to reuse a retired email while an old
token remains cryptographically valid could bind that token to the new identity. The global database uniqueness rules
therefore include retired rows, and public registration neither reactivates nor replaces them. This reservation is not a
complete personal-data retention policy. Changing it requires an explicit redesign of JWT identity binding and the
associated token migration and compatibility contract, rather than a partial unique index or email anonymization alone.

## JWT subject migration decision

The bearer-token subject remains the normalized email. This is an explicit compatibility decision rather than an
endorsement of mutable business identifiers as the long-term token identity. The persisted User UUID is generated once,
is not updateable, survives profile changes, and is suitable as the target immutable identity. The current authentication
boundary cannot consume it, however: `JwtAuthenticationFilter` passes `sub` to `UserDetailsService.loadUserByUsername`,
whose only supported subject lookup is the active User and roles query by normalized email. `CurrentUserProvider` and the
module-owned `CurrentUserFacade` also intentionally expose the authenticated principal as an email-backed boundary.

Repository evidence does not establish a coordinated deployment time at which all outstanding email-subject tokens may
be invalidated or a durable cutoff after which legacy tokens must fail. Although production configures a fixed one-hour
access-token lifetime, replicas can issue tokens until they are drained, and signing-key rollover may independently keep
the signing key for an outstanding token valid. A hard cutover would therefore invalidate legitimately issued tokens.
An unbounded dual parser would silently turn a temporary migration mechanism into a permanent contract. Inferring the
subject type by attempting to parse it as a UUID is also rejected because it gives token contents, rather than an explicit
schema contract, control over the lookup path.

Accordingly, UUID-subject issuance is deferred. A future migration requires all of the following in one reviewed change:

- an explicit version or identity-type claim on new tokens, with unknown or malformed values failing closed;
- a narrow active-User lookup by UUID that fetches current roles and never falls back to email for a UUID token;
- an operator-owned rollout start/cutoff contract that accounts for the last legacy-token issuer plus the maximum token
  lifetime, independently of signing-key rotation;
- compatibility tests proving that legacy email tokens resolve only the same active, permanently email-reserved User,
  both token types fail after retirement, and database authorities override token role metadata; and
- retirement/replay proof against PostgreSQL before legacy acceptance is removed.

The permanent retired-email reservation remains unchanged during and after this audit. UUID migration and any future
email-reuse policy are separate lifecycle/security decisions. No application or documented public API promises a JWT
subject format; the login response treats the JWT as an opaque token. Within this repository, only the JWT provider reads
`sub`, only the authentication filter consumes that value, and the tests inspect it to prove the current contract.
