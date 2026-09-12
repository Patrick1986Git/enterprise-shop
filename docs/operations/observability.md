# Observability

## Request correlation

`RequestIdFilter` provides lightweight HTTP/log correlation for every request.

| Behavior | Current implementation |
| --- | --- |
| Incoming header | Reads `X-Request-Id`. |
| Accepted caller value | Trimmed, non-blank, printable ASCII, max 100 characters. |
| Generated value | UUID when the header is missing or invalid. |
| MDC key | `requestId`. |
| Response header | Always sets `X-Request-Id` to the accepted/generated value. |
| CORS exposure | `X-Request-Id` is exposed to browsers. |

## Logging

The console log pattern includes the request correlation id:

```text
requestId=%X{requestId}
```

Operational policy:

- Do not log JWT tokens, passwords, card data, Stripe secrets, or webhook secrets.
- Do not add high-cardinality request/user/payment identifiers as metric tags.
- Request/response body logging is intentionally not part of the current baseline.

## Actuator and Prometheus

Production explicitly owns an HTTP exposure allowlist containing only `health`, `info`, `metrics`, and `prometheus`;
it does not rely on inherited exposure defaults. All endpoints share the application port. The repository does not
define a separate management network, proxy, or Prometheus deployment, so deployment owners must decide whether the
authenticated metrics path also requires network isolation.

| Endpoint | Access | Purpose |
| --- | --- | --- |
| `/actuator/health` | Public | Health status. Details are shown when authorized. |
| `/actuator/info` | Admin | Application info. |
| `/actuator/metrics` | Admin | Metrics index and individual meter lookup. |
| `/actuator/prometheus` | Admin | Prometheus scrape endpoint provided by the Prometheus registry. |

Anonymous aggregate health intentionally discloses only `status` and the `liveness`/`readiness` probe group names;
component names and details are not returned. Probe responses disclose only `status`. Liveness contains only application
liveness state. Production readiness contains application readiness and the standard database contributor, while SMTP,
Stripe, and ordinary asynchronous backlog state are excluded. An ADMIN can receive authorized health details, including
operational component names and statuses.

`info` remains exposed for authenticated ADMIN diagnostics; no repository-defined info contributor publishes build,
Git, environment, customer, or credential data. `metrics` and `prometheus` remain exposed because they are the
documented operator inventory and scrape surfaces, but both require `ROLE_ADMIN`. This is an application authorization
boundary, not evidence of a deployment-owned private monitoring network.

Configuration/introspection and diagnostic endpoints—including `env`, `configprops`, `beans`, `mappings`, `heapdump`,
`threaddump`, and `logfile`—are outside the production allowlist and therefore are not HTTP-exposed even to an ADMIN.
Management responses must never contain datasource or Flyway passwords, JWT secrets, Stripe secrets, SMTP passwords,
authenticated connection URLs, authorization headers, cookies, request bodies, or exception messages containing such
values. Spring Boot sanitization is not the control for these endpoints; non-exposure is.

## Application metric inventory

Counters are monotonic process-lifetime event totals. Calculate rates in the monitoring/query layer; a counter is not a
current backlog measurement. Gauges are point-in-time database state, return zero when no matching row exists, and
clamp age to zero if the application clock is behind a stored timestamp.

| Metric | Type | Producer | Tags and finite values | Meaning |
| --- | --- | --- | --- | --- |
| `shop.checkout.total` | Counter | `OrderCheckoutProcessor` | `result`: `attempt`, `success`, `failure` | Checkout attempts and terminal in-process outcomes. |
| `shop.payment_intent.total` | Counter | `PaymentServiceImpl` | `result`: `created`, `reused`, `failed` | Payment-intent initialization outcomes. |
| `shop.webhook.total` | Counter | `PaymentServiceImpl` | `result`: `received`, `processed`, `ignored`, `duplicate`, `failed` | Validly parsed arrivals and webhook handling outcomes. A signature/payload rejection contributes only `failed`, not `received`. |
| `shop.stripe.payment_conflict.total` | Counter | `StripePaymentConflictRecorder` | `reason`: `TERMINAL_STATE_CONTRADICTION` | Newly persisted authenticated provider/local contradiction observations. Same-event retries do not increment it. |
| `shop.business_exception.total` | Counter | `GlobalExceptionHandler` | `error_code`: repository-defined business error codes; `status_class`: `4xx`, `5xx`, `other` | Business exceptions translated to API errors. Error messages are never tags. |
| `shop.order.reservation_expiration.total` | Counter | `ReservationExpirationProcessor` | `outcome`: `claimed`, `terminal_noop`, `provider_succeeded`, `provider_already_canceled`, `provider_canceled`, `provider_pending`, `failed`, `retry`, `retry_exhausted` | Reservation-expiration processing transitions and outcomes. A claim can increment more than one outcome. |
| `shop.order.reservation_expiration.inventory_units_released` | Counter | `ReservationExpirationProcessor` | none | Inventory units released by successful reservation expiration. |
| `shop.order.reservation_expiration.recovery.total` | Counter | `ReservationExpirationRecoveryService` | `outcome`: `requeued`, `terminal_noop` | ADMIN recovery outcomes for failed expiration work. |
| `shop.order.reservation_expiration.failed.count` | Gauge | `ReservationExpirationMetrics` | none | Current terminal failed reservation-work rows. |
| `shop.order.reservation_expiration.failed.oldest.age.seconds` | Gauge | `ReservationExpirationMetrics` | none | Age since `failed_at` of the oldest current failed reservation-work row. |
| `shop.outbox.actionable.count` | Gauge | `OutboxEventMetrics` | none | Current PENDING events whose `next_attempt_at` is absent or due. Future retries are excluded. |
| `shop.outbox.actionable.oldest.age.seconds` | Gauge | `OutboxEventMetrics` | none | Time since the oldest current event became actionable: `next_attempt_at` for retries, otherwise `created_at`. |
| `shop.outbox.dead_letter.count` | Gauge | `OutboxEventMetrics` | none | Current DEAD_LETTER events requiring operator investigation or requeue. |
| `shop.outbox.dead_letter.oldest.age.seconds` | Gauge | `OutboxEventMetrics` | none | Time since the oldest current dead letter's terminal `last_attempt_at`. |
| `shop.notification.actionable.count` | Gauge | `NotificationDeliveryMetrics` | none | Current due PENDING notifications plus PROCESSING notifications with expired claims. Future deliveries and live claims are excluded. |
| `shop.notification.actionable.oldest.age.seconds` | Gauge | `NotificationDeliveryMetrics` | none | Time since the oldest matching notification became actionable: due/retry time (or creation) for PENDING, claim expiry for PROCESSING. |
| `shop.notification.failed.count` | Gauge | `NotificationDeliveryMetrics` | none | Current terminal FAILED notifications requiring operator investigation or requeue. |
| `shop.notification.failed.oldest.last_attempt.age.seconds` | Gauge | `NotificationDeliveryMetrics` | none | Time since `last_attempt_at` for the current FAILED notification with the oldest delivery-attempt timestamp. This is attempt age, not exact time spent in FAILED. |

All tag value sets above are defined by repository code. No tag contains a user, order, payment, provider-event,
email, JWT subject, request, arbitrary provider value, exception message, payload, or notification content. Tests use a
`SimpleMeterRegistry` for exact names/types/tags and PostgreSQL repository tests prove the scalar backlog query semantics.

The application also relies on Spring Boot's standard HTTP/JVM/process/data-source meters and standard health
contributors. Their names and labels are framework-owned rather than repository-defined; deployments should pin and
review the framework version before treating those as a durable external contract.

Spring Boot 4.1.1 automatically binds the single HikariCP 7.0.2 application datasource to Micrometer. Hikari publishes
the following meters with its low-cardinality `pool` tag; Spring Boot additionally publishes the first four capacity
gauges under `jdbc.connections.*` with datasource `name`:

| Hikari meter | Type | Operational meaning |
| --- | --- | --- |
| `hikaricp.connections.active` | Gauge | Connections currently borrowed from the pool. |
| `hikaricp.connections.idle` | Gauge | Established connections currently available. |
| `hikaricp.connections.pending` | Gauge | Threads waiting to acquire a connection. |
| `hikaricp.connections` | Gauge | Total established pool connections. |
| `hikaricp.connections.max` / `hikaricp.connections.min` | Gauge | Effective per-replica configured ceiling and idle floor. |
| `hikaricp.connections.acquire` | Timer | Successful connection acquisition latency. |
| `hikaricp.connections.usage` | Timer | Time connections remain borrowed. |
| `hikaricp.connections.creation` | Timer | Physical connection creation latency. |
| `hikaricp.connections.timeout` | Counter | Acquisitions that reached `connectionTimeout`. |

The generic gauges are `jdbc.connections.active`, `jdbc.connections.idle`, `jdbc.connections.max`, and
`jdbc.connections.min`. No custom pool gauges are needed. Pending demand plus acquisition latency and timeouts reveals
saturation pressure; usage and creation timing help distinguish long-held connections, slow creation, and capacity
contention. These process-local signals must be aggregated with replica count and database/proxy telemetry for the
database-wide budget. Do not attach SQL, database usernames, request/user/tenant identifiers, or arbitrary pool values
as tags, and derive no alert threshold from the repository defaults.

The running embedded Tomcat connector is covered by standard Micrometer meters: `tomcat.threads.busy`,
`tomcat.threads.current`, `tomcat.threads.config.max`, `tomcat.connections.current`,
`tomcat.connections.keepalive.current`, and `tomcat.connections.config.max`. Standard HTTP observations publish
`http.server.requests` count and duration, while connector-wide request, error, and byte totals/timing are available
under `tomcat.global.*`. There is no repository-defined rejection gauge and no standard application meter that fully
observes an OS accept-backlog overflow. Diagnose overload by correlating edge/host connection evidence, these Tomcat
meters, HTTP outcomes, readiness, and the Hikari meters above. See
[HTTP admission capacity and backpressure](./http-capacity.md) for queue and failure semantics. No duplicate gauges or
numeric alert thresholds are defined.

## Request parsing visibility

A malformed body that reaches Spring MVC can appear in standard HTTP status metrics, but a connector-rejected oversized header, request-read timeout/disconnect, or edge-rejected body may occur before application instrumentation. Correlate fixed-cardinality edge rejection reasons and sanitized Tomcat parser/error logs with connector totals; do not log header/body contents or tag metrics with payload size, source IP, request ID, raw URI, email, or header values. See [Inbound HTTP request resource bounds](./http-request-bounds.md).

## Asynchronous degradation and alert contract

Backlog is observed through metrics, not health. A short backlog must not remove a replica from traffic, and a database
failure continues to affect readiness without automatically failing liveness. An HTTP-ready process with a stalled
scheduler is revealed when actionable count remains non-zero and oldest actionable age keeps increasing. Terminal
count and age distinguish work requiring investigation/requeue from retryable work.

`app.outbox.processing.enabled=false` and `app.notification.delivery.enabled=false` may be intentional maintenance
choices. They are not health failures and no enabled-state gauge is added: deployment configuration validation is the
authoritative control. If either worker is unintentionally disabled while work arrives, its actionable age exposes the
resulting degradation. Likewise, `app.notification.smtp.enabled=false` intentionally selects the no-op sender and is
not inherently an error; a production deployment that requires email must validate that configuration separately.

This repository owns no production alert manager and defines no numeric SLOs, thresholds, routes, or dashboards.
Deployment owners should select objectives and alert conditions such as:

- actionable outbox or notification age exceeding the selected processing/delivery objective;
- dead-letter or failed work remaining non-zero beyond the approved investigation window (evaluate persistence of the
  count over time; notification last-attempt age is not an exact FAILED-state duration);
- sustained checkout, payment-intent, webhook, reservation-processing, or recovery failure rates exceeding the
  selected error budget.

The counters distinguish observed traffic/outcomes, but cannot alone distinguish no traffic from a broken upstream
route. Webhook `failed`, `ignored`, and `duplicate` rates separate those observed outcomes; they do not measure Stripe
availability or guarantee end-to-end provider delivery. The deployment owns checkout availability, payment-intent
success, webhook delay, outbox delay, notification delay, and reservation recovery objectives and must combine these
application signals with traffic/platform evidence appropriate to its environment.

## Query cost and operator diagnostics

Each backlog gauge executes a scalar `COUNT` or `MIN`; no entity, JSON outbox payload, notification body, recipient, or
error body is loaded. Existing indexes support the predicates: outbox `(status, next_attempt_at)`,
`(status, created_at)`, and `(status, last_attempt_at)`; notification `(status, next_attempt_at, created_at)`,
`(claim_expires_at)`, and `(status, last_attempt_at)`. No migration or additional write-amplifying index is required.

Current reservation retry exhaustion and Stripe failures have warning/error logs with identifiers useful for
investigation. Outbox and notification failures persist bounded workflow state and error details for protected ADMIN
inspection but do not currently emit dedicated failure logs. This avoids logging outbox payloads, notification bodies,
recipients, raw webhook bodies, secrets, or client secrets; metrics provide the proactive signal and protected records
provide drill-down context.

Terminal Stripe success contradictions additionally persist immutable, sanitized observations for protected ADMIN
list/detail inspection. Alert on the finite-reason conflict counter according to deployment-owned objectives, then use
the stored Stripe event/PaymentIntent and local Order/Payment identifiers for drill-down. The generic webhook failure
counter remains broader by design. No unresolved-conflict gauge exists because observations are immutable history, not
a repository-defined open/resolved workflow.

Notification `last_attempt_at` consistently means the delivery-attempt instant. A claimed delivery writes it when the
claim begins; `finalizeFailed` later preserves that instant whether it schedules a retry or enters FAILED. The legacy
`markFailed` and `markDeliveryAttemptFailed` paths write it as they record their failed attempt. Expired PROCESSING
claims terminalized after exhausting attempts also preserve the earlier claim-attempt instant. ADMIN requeue leaves
FAILED and clears `last_attempt_at`. Consequently the FAILED gauge deliberately reports oldest last-attempt age rather
than claiming to measure exact time since entry into FAILED; no additional persistence column or index is justified.

Tagging rules:

- Keep tags low-cardinality.
- Do not tag metrics with user ids, order ids, payment ids, emails, JWT subjects, request ids, or raw exception messages.
- Use logs with `requestId` for per-request debugging rather than high-cardinality metric labels.

## Smoke checks

Use an authenticated admin request for protected actuator endpoints.

```bash
curl -i http://localhost:8080/actuator/health
curl -i -H "Authorization: Bearer <admin-token>" http://localhost:8080/actuator/prometheus
```
