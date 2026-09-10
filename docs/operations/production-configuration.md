# Production configuration contract

This is the operator-facing inventory for every environment placeholder used by
`src/main/resources/application-prod.yml`. That Spring configuration remains authoritative for binding; this document
records deployment ownership, meaning, and validation. A repository policy check compares the table to the file on every
normal CI run. `.env.example` is a local-development convenience and is not a production manifest.

A deployment may inject these environment variables directly or have a secret manager inject them. The repository does
not prescribe an orchestrator or store production values. **Secret** rows must come from deployment-controlled secret
storage and must not be committed or logged. **Sensitive** rows are operational details that may reveal infrastructure or
policy but are not credentials. **Non-secret** rows need normal configuration integrity, not secret handling.

The `Placeholder syntax` column is machine checked: `required` means `${VAR}`, `defaulted` means `${VAR:value}` (including
a nested fallback), and `empty-default` means `${VAR:}`. The `Runtime requirement` column adds binding and custom-validator
semantics. Missing required placeholders stop Spring startup. Invalid numbers, booleans, durations, URLs, or validated
relationships also stop startup unless a row explicitly describes a later operational failure.

## Canonical inventory

Keep exactly one row per variable. For a variable used by multiple properties, list its property paths in source order,
separated by `, `. Do not add production values to this table.

| Variable | Spring property | Placeholder syntax | Runtime requirement | Classification | Format / repository fallback | Owner, validation, and deeper guidance |
| --- | --- | --- | --- | --- | --- | --- |
| `DATABASE_URL` | `spring.datasource.url, spring.flyway.url` | required | Required; also the effective Flyway URL when `FLYWAY_URL` is absent. | Sensitive | PostgreSQL JDBC URL; no repository default. | Database/platform; connectivity and driver parsing fail startup. See [database operations](./database.md). |
| `DATABASE_USERNAME` | `spring.datasource.username` | required | Required and non-blank; must differ from the Flyway user. | Sensitive | Runtime PostgreSQL role name; no default. | Database/platform; the production Flyway identity validator and database ownership validator fail startup on unsafe identity/ownership. See [database operations](./database.md). |
| `DATABASE_PASSWORD` | `spring.datasource.password` | required | Required by placeholder; authentication is proven when the datasource starts. | Secret | Runtime PostgreSQL credential; no default. | Secret store/database owner; missing, invalid, or rejected credentials fail startup without being documented here. See [database operations](./database.md). |
| `DATABASE_MAXIMUM_POOL_SIZE` | `spring.datasource.hikari.maximum-pool-size` | required | Required. Must be positive and at least `DATABASE_MINIMUM_IDLE`. | Sensitive | Integer connections per replica; no default. | Platform/database capacity; custom production Hikari validator. See [HTTP and downstream capacity](./http-capacity.md) and [database operations](./database.md). |
| `DATABASE_MINIMUM_IDLE` | `spring.datasource.hikari.minimum-idle` | required | Required. Must be non-negative and no greater than the maximum pool size. | Sensitive | Integer idle connections per replica; no default. | Platform/database capacity; custom production Hikari validator. See [HTTP and downstream capacity](./http-capacity.md). |
| `DATABASE_CONNECTION_TIMEOUT_MILLISECONDS` | `spring.datasource.hikari.connection-timeout` | required | Required. Must be at least 250 ms. | Sensitive | Integer milliseconds; no default. | Platform/database latency policy; custom production Hikari validator. See [HTTP and downstream capacity](./http-capacity.md). |
| `FLYWAY_URL` | `spring.flyway.url` | defaulted | Optional override; falls back exactly to `${DATABASE_URL}`. | Sensitive | PostgreSQL JDBC URL; fallback is `DATABASE_URL`. | Database migration owner; connectivity and driver parsing fail startup. Both variables are inventoried because one selects the migration endpoint while the fallback remains a separately required runtime input. See [migrations](./migrations.md). |
| `FLYWAY_USER` | `spring.flyway.user` | required | Required and non-blank; must differ from `DATABASE_USERNAME`. | Sensitive | Migration PostgreSQL role name; no default. | Database migration owner; custom production Flyway identity validator. See [database operations](./database.md) and [migrations](./migrations.md). |
| `FLYWAY_PASSWORD` | `spring.flyway.password` | required | Required and non-blank. | Secret | Migration-role credential; no default. | Secret store/database migration owner; custom production Flyway identity validator and database authentication fail startup. See [migrations](./migrations.md). |
| `SERVER_TOMCAT_THREADS_MAX` | `server.tomcat.threads.max` | required | Required and positive. | Sensitive | Integer worker-pool threads per replica; no default. | Platform HTTP capacity; custom production Tomcat validator. See [HTTP capacity](./http-capacity.md). |
| `SERVER_TOMCAT_MAX_CONNECTIONS` | `server.tomcat.max-connections` | required | Required and positive. | Sensitive | Integer connections per replica; no default. | Platform HTTP admission capacity; custom production Tomcat validator. See [HTTP capacity](./http-capacity.md). |
| `SERVER_TOMCAT_ACCEPT_COUNT` | `server.tomcat.accept-count` | required | Required and positive. | Sensitive | Integer queued connections per replica; no default. | Platform HTTP admission capacity; custom production Tomcat validator. See [HTTP capacity](./http-capacity.md). |
| `SERVER_TOMCAT_CONNECTION_TIMEOUT` | `server.tomcat.connection-timeout` | required | Required and positive. | Sensitive | Spring duration (for example ISO-8601); no default. | Platform slow-client policy; custom production Tomcat validator. See [HTTP request bounds](./http-request-bounds.md) and [production edge](./production-edge.md). |
| `JWT_KEY_ID` | `security.jwt.key-id` | required | Required; 1–64 characters matching `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. Must differ from a configured previous ID. | Non-secret | Stable active key identifier; no default. | Security/key lifecycle owner; JWT properties/provider validation fails startup. See [JWT rotation](./jwt-key-rotation.md). |
| `JWT_SECRET` | `security.jwt.secret` | required | Required, non-blank RFC 4648 Base64 encoding of at least 256 bits. | Secret | Active HMAC key material; no default. | Secret store/security owner; JWT properties/provider validation fails startup without exposing the value. See [JWT rotation](./jwt-key-rotation.md). |
| `JWT_PREVIOUS_KEY_ID` | `security.jwt.previous-key-id` | empty-default | Optional as a pair; conditionally required when `JWT_PREVIOUS_SECRET` is set. | Non-secret | Same identifier format as active ID; empty by default. | Security/key lifecycle owner; provider requires both previous values and distinct active/previous IDs. See [JWT rotation](./jwt-key-rotation.md). |
| `JWT_PREVIOUS_SECRET` | `security.jwt.previous-secret` | empty-default | Optional as a pair; conditionally required when `JWT_PREVIOUS_KEY_ID` is set. | Secret | Previous HMAC key material with active-secret encoding/strength; empty by default. | Secret store/security owner; provider requires both previous values and validates the key. See [JWT rotation](./jwt-key-rotation.md). |
| `STRIPE_SECRET_KEY` | `stripe.api-key` | required | Required by placeholder; rejected credentials fail Stripe operations. | Secret | Stripe server API credential; no default. | Secret store/payments owner; external Stripe connectivity and credential validity require deployment testing. See [Stripe network boundary](../architecture/stripe-network-boundary.md). |
| `STRIPE_WEBHOOK_SECRET` | `stripe.webhook-secret` | required | Required by placeholder; incorrect material rejects webhook signatures at runtime. | Secret | Stripe endpoint-signing secret; no default. | Secret store/payments owner; test against the configured external webhook endpoint. See [Stripe network boundary](../architecture/stripe-network-boundary.md). |
| `STRIPE_PUBLIC_KEY` | `stripe.public-key` | required | Required by placeholder. | Non-secret | Stripe publishable key; no default. | Payments/frontend release owner; public identifier, not secret material. See [Stripe network boundary](../architecture/stripe-network-boundary.md). |
| `STRIPE_CONNECT_TIMEOUT` | `stripe.network.connect-timeout` | required | Required; duration must be positive and fit a positive 32-bit millisecond value. | Sensitive | Spring duration; no default. | Payments/platform latency policy; `StripeNetworkProperties` validates on client construction. See [Stripe network boundary](../architecture/stripe-network-boundary.md). |
| `STRIPE_READ_TIMEOUT` | `stripe.network.read-timeout` | required | Required; duration must be positive and fit a positive 32-bit millisecond value. | Sensitive | Spring duration; no default. | Payments/platform latency policy; `StripeNetworkProperties` validates on client construction. See [Stripe network boundary](../architecture/stripe-network-boundary.md). |
| `STRIPE_MAX_NETWORK_RETRIES` | `stripe.network.max-network-retries` | required | Required and zero or positive. | Sensitive | Integer retry count; no default. | Payments/platform retry policy; `StripeNetworkProperties` validation. See [Stripe network boundary](../architecture/stripe-network-boundary.md). |
| `CORS_ALLOWED_ORIGINS` | `app.security.cors.allowed-origins` | empty-default | Optional; empty means cross-origin browser access is denied. | Sensitive | Comma-separated explicit HTTP(S) origins; empty by default. | Security/edge owner; `CorsProperties` rejects wildcards and origins with user info, paths, queries, or fragments. See [production edge](./production-edge.md) and [security architecture](../architecture/security-architecture.md). |
| `ORDER_RESERVATION_DURATION` | `app.order.reservation-expiration.duration` | defaulted | Optional; must be positive. | Non-secret | Spring duration; default `PT30M`. | Order worker owner; configuration-properties setter validation. See [application lifecycle](./application-lifecycle.md). |
| `ORDER_RESERVATION_FIXED_DELAY` | `app.order.reservation-expiration.fixed-delay` | defaulted | Optional; must be positive. | Non-secret | Spring duration; default `PT10S`. | Order worker scheduling owner; configuration-properties setter validation. See [application lifecycle](./application-lifecycle.md). |
| `ORDER_RESERVATION_RETRY_DELAY` | `app.order.reservation-expiration.retry-delay` | defaulted | Optional; must be positive. | Non-secret | Spring duration; default `PT1M`. | Order recovery owner; configuration-properties setter validation. See [application lifecycle](./application-lifecycle.md). |
| `ORDER_RESERVATION_CLAIM_LEASE` | `app.order.reservation-expiration.claim-lease` | defaulted | Optional; must be positive. | Non-secret | Spring duration; default `PT5M`. | Order recovery owner; configuration-properties setter validation. See [application lifecycle](./application-lifecycle.md). |
| `ORDER_RESERVATION_BATCH_SIZE` | `app.order.reservation-expiration.batch-size` | defaulted | Optional; must be positive. | Sensitive | Integer reservations per polling pass; default `25`. | Order/database capacity owner; configuration-properties setter validation. See [application lifecycle](./application-lifecycle.md). |
| `ORDER_RESERVATION_MAX_ATTEMPTS` | `app.order.reservation-expiration.max-attempts` | defaulted | Optional; must be positive. | Non-secret | Integer attempt count; default `10`. | Order recovery owner; configuration-properties setter validation. See [application lifecycle](./application-lifecycle.md). |
| `NOTIFICATION_SMTP_ENABLED` | `app.notification.smtp.enabled` | defaulted | Optional; `false` selects the no-op sender, while `true` requires a non-blank `spring.mail.host`. | Non-secret | Boolean; default `false`. | Notification/platform owner; incomplete local transport configuration fails startup and transport access must be tested externally. See [notification architecture](../architecture/outbox-and-notifications.md). |
| `NOTIFICATION_SMTP_FROM` | `app.notification.smtp.from` | defaulted | Optional syntactically; operationally required to be a provider-accepted sender when SMTP is enabled. | Non-secret | Sender address; default `no-reply@enterprise-shop.local`. | Notification/mail owner; provider rejection occurs during delivery rather than repository startup validation. See [notification architecture](../architecture/outbox-and-notifications.md). |
| `NOTIFICATION_SMTP_CONNECTION_TIMEOUT` | `app.notification.smtp.connection-timeout` | defaulted | Optional; positive, at most 2,147,483,647 ms, and shorter than the notification delivery claim duration when SMTP is enabled. | Sensitive | Spring duration; default `PT30S`. | Notification/platform latency owner; SMTP properties and delivery configuration validate. See [notification architecture](../architecture/outbox-and-notifications.md). |
| `NOTIFICATION_SMTP_READ_TIMEOUT` | `app.notification.smtp.read-timeout` | defaulted | Optional; positive, at most 2,147,483,647 ms, and shorter than the notification delivery claim duration when SMTP is enabled. | Sensitive | Spring duration; default `PT30S`. | Notification/platform latency owner; SMTP properties and delivery configuration validate. See [notification architecture](../architecture/outbox-and-notifications.md). |
| `NOTIFICATION_SMTP_WRITE_TIMEOUT` | `app.notification.smtp.write-timeout` | defaulted | Optional; positive, at most 2,147,483,647 ms, and shorter than the notification delivery claim duration when SMTP is enabled. | Sensitive | Spring duration; default `PT30S`. | Notification/platform latency owner; SMTP properties and delivery configuration validate. See [notification architecture](../architecture/outbox-and-notifications.md). |

## Conditional framework-bound SMTP configuration

The 35-row canonical inventory above remains limited to `${...}` placeholders explicitly present in
`application-prod.yml`. SMTP transport settings are instead owned by Spring Boot's standard `spring.mail.*` binding and
may be supplied through relaxed environment binding without appearing as placeholders in that file. They are therefore
listed separately and are not inputs to the placeholder drift validator.

When `NOTIFICATION_SMTP_ENABLED=true`, `SPRING_MAIL_HOST` (`spring.mail.host`) is required and must be non-blank. The
application validates only that local invariant and does not connect to the server during startup. The following settings
remain deployment/provider choices:

| Environment binding | Spring property | Requirement | Classification |
| --- | --- | --- | --- |
| `SPRING_MAIL_HOST` | `spring.mail.host` | Required when notification SMTP is enabled. | Sensitive infrastructure configuration; not a credential. |
| `SPRING_MAIL_PORT` | `spring.mail.port` | Optional; if absent, SMTP uses its protocol default port. | Non-secret. |
| `SPRING_MAIL_USERNAME` | `spring.mail.username` | Optional; required only by the selected provider/relay policy. | Sensitive identity configuration; not necessarily secret. |
| `SPRING_MAIL_PASSWORD` | `spring.mail.password` | Optional; required only by the selected provider/relay policy. | Secret; inject from deployment-controlled secret storage. |
| `SPRING_MAIL_PROTOCOL` | `spring.mail.protocol` | Optional; Spring Boot defaults to `smtp`. | Non-secret. |
| `SPRING_MAIL_SSL_ENABLED` | `spring.mail.ssl.enabled` | Optional; deployment-owned transport-security policy. | Sensitive operational security configuration. |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | `spring.mail.properties[mail.smtp.auth]` | Optional; deployment-owned authentication policy. | Sensitive operational security configuration. |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | `spring.mail.properties[mail.smtp.starttls.enable]` | Optional; deployment-owned STARTTLS policy. | Sensitive operational security configuration. |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED` | `spring.mail.properties[mail.smtp.starttls.required]` | Optional; deployment-owned STARTTLS policy. | Sensitive operational security configuration. |

Spring Boot does not supply username, password, authentication, STARTTLS, or implicit TLS requirements. The resolved SMTP
implementation defaults authentication, STARTTLS, and implicit TLS to disabled. This repository deliberately does not
replace those provider-specific decisions with a second transport-properties abstraction. Repository-owned
`NOTIFICATION_SMTP_*_TIMEOUT` values are applied directly to the effective Jakarta Mail session after Spring Boot creates
the sender.

## Deployment handoff

Before rollout, the deployment owner must:

1. supply every `required` row, including secrets through an approved injection mechanism;
2. decide whether optional overrides are needed and preserve the documented defaults otherwise;
3. coordinate Tomcat, Hikari, database, Stripe, worker, SMTP, edge, and termination budgets rather than tuning each in
   isolation;
4. keep runtime and migration database identities separate and grant them only their documented privileges;
5. test database/Flyway connectivity, Stripe credentials/webhooks, CORS origins, and enabled SMTP transport against the
   actual external infrastructure; and
6. exercise readiness, graceful shutdown, rollback, key rotation, and recovery procedures described in the linked runbooks.

Repository startup validation proves binding and the listed invariants. It cannot prove external capacity, routing,
credential acceptance, provider sender policy, certificate trust, DNS, firewall rules, or secret rotation by the deployment.
