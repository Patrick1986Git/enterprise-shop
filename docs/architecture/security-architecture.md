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

## Stateless API and CSRF

- The owner-approved CSRF exemption is limited to `/api/v1/**`, including public registration/login and authenticated customer/ADMIN mutations. CSRF remains enabled outside that scope. This supersedes the former webhook-only exception within the versioned API; it does not authorize global CSRF disabling.
- Protected API requests authenticate only through an explicitly supplied `Authorization: Bearer` header. Cookies, `JSESSIONID`, and security contexts saved in HTTP sessions cannot authenticate them. Form login and HTTP Basic remain disabled. Registration assigns only `ROLE_USER`; the offline administrator lifecycle is unchanged.
- Browsers automatically attach some credentials, such as cookies, but do not automatically supply this application's bearer header. Login returns a JWT in JSON and establishes no cookie or authenticated session. Clients must deliberately consume that response and supply the token on subsequent requests. The exemption therefore also covers login without introducing an ambient login identity or account-switching mechanism.
- API clients and local Swagger UI do not acquire or submit CSRF tokens. Clients using only the API create no CSRF session or cookie. If a client already holds a CSRF session created outside the exemption, unchanged Spring Security session handling can rotate its existing session ID on a bearer-authenticated request; neither that session nor its returned cookie authenticates a later API request. Missing, invalid, or previously obtained CSRF proof has no authority over API authentication or authorization.
- CORS is an independent browser origin restriction, not a substitute for CSRF protection, authentication, or authorization. The explicit origin allowlist and credential setting remain unchanged; allowing credentialed CORS does not make cookies API authentication credentials.
- Introducing cookie/session/Basic authentication or any other automatically supplied authentication mechanism requires a new CSRF security review **before activation**, including registration/login and any affected API paths. Future surfaces outside the exemption retain CSRF protection; they must not be moved into it without that review.

## Webhook authenticity

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

V52 implements an unwired, non-public local/test password-recovery foundation. Its 15-minute verifier-only challenges
exclude disabled, retired and ADMIN Users and atomically advance credential_version on consumption. Only test fixtures
construct its package-private service and capture raw material in memory. End-user recovery remains unsupported: there is
no controller, real courier, delivery worker or activation property. The [recovery boundary](./password-recovery-boundary.md)
defines the approved lifecycle, existing lock order, restore invalidation and remaining public/production decisions.

V53's separately approved [local sole-ADMIN credential recovery](./local-administrator-credential-recovery.md) requires
current-password enrollment, independent emergency proof, owner-created ten-minute authority and a distinct reset-only
login. It preserves the V51 bootstrap boundary and V52 ADMIN exclusion and adds no HTTP API or production activation.

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

User-writing commands acquire the shared administrator domain lock before the existing per-User PostgreSQL
transaction-scoped lifecycle advisory lock and repeat the
active lookup after acquiring it. They therefore serialize with profile updates, password changes, commerce operations,
retirement, and each other. Retirement remains permanent: it wins against a waiting state command by making the repeated
active lookup fail, and no enable command can find or restore the soft-deleted User. V51 adds database continuity guards
around these existing account-state and credential-version fields.

Registration assigns only `ROLE_USER` through a constrained database function; runtime role DML is denied. The approved
local owner can manually bootstrap or recover an administrator through a separately authenticated offline PostgreSQL
operator. Promotion preserves roles, advances the credential version, and writes database-authenticated evidence
atomically. Previously issued tokens fail; a new login reflects the persisted roles. There is no additional-admin API.

Disablement and retirement reject removal of the final usable administrator for self-action and action on another admin.
Both service checks and PostgreSQL guards serialize these transitions. Zero administrators remains valid before bootstrap
or after recovery to an older backup; re-enabling a disabled ADMIN through runtime operations cannot recover from zero.
The complete lock, privilege, audit and production boundaries are recorded in the
[administrator lifecycle boundary](./administrator-lifecycle-boundary.md), with the approved
[local operator runbook](../operations/local-administrator.md).

The private promotion ledger records committed successful operations and has no deletion path. Existing workflow action
logs remain separate. Independently durable rejected-attempt evidence and production operator procedures remain
unapproved. Application logs must never contain passwords, JWTs, request bodies or raw credentials.

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
