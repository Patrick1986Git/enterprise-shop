# Observability baseline

## 1) Request correlation (`X-Request-Id` + MDC)

### Purpose
`X-Request-Id` is used as a per-request correlation identifier for HTTP traffic. It enables fast correlation between:
- client reports,
- API responses,
- server logs.

### Runtime behavior
For every HTTP request, `RequestIdFilter` enforces one stable request identifier:
- if client sends a valid `X-Request-Id`, the value is reused,
- if header is missing, blank, too long, or invalid, server generates a UUID,
- resolved identifier is added to response header `X-Request-Id`.

### MDC log correlation
Resolved request id is stored in MDC key `requestId` for request scope, so all logs produced during request handling can be correlated.

After request completion, MDC is always cleared to prevent leakage across requests.

### Response visibility
Clients always receive the resolved id in `X-Request-Id` response header, including error responses handled by `GlobalExceptionHandler`.

## 2) Spring Boot Actuator baseline

### Exposed endpoints
The baseline exposes only:
- `/actuator/health`
- `/actuator/info`
- `/actuator/metrics`

### Access model
- `/actuator/health` — public (`permitAll`),
- `/actuator/info` — only `ROLE_ADMIN`,
- `/actuator/metrics` — only `ROLE_ADMIN`.

Health details are configured with `show-details: when_authorized`.

### Test boundary
Actuator authorization is verified only in dedicated integration-style security tests where Actuator auto-configuration and mappings are present.

`SecurityConfigWebMvcTest` remains focused on business/API controller security and must not assert `/actuator/...` endpoints, because this slice does not provide real Actuator mappings.

## 3) Local curl examples

Without token (anonymous):

```bash
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/info
curl -i http://localhost:8080/actuator/metrics
```

With admin JWT:

```bash
curl -i -H "Authorization: Bearer <ADMIN_JWT>" http://localhost:8080/actuator/info
curl -i -H "Authorization: Bearer <ADMIN_JWT>" http://localhost:8080/actuator/metrics
```

Request id visibility:

```bash
curl -i -H "X-Request-Id: demo-123" http://localhost:8080/api/v1/products
```

## 4) Out of scope for current baseline (intentional)

At this stage we intentionally do **not** add:
- Prometheus registry,
- Grafana dashboards,
- deployment/infrastructure changes,
- Docker build/publish changes.

Goal of this baseline is only to keep minimal, secure, testable observability in the existing application architecture.

## 5) First business metrics available

The first low-risk business counters are now exposed through Spring Boot Actuator metrics endpoint:

- `shop.checkout.total` with `result=attempt|success|failure`,
- `shop.payment_intent.total` with `result=created|reused|failed`.

These metrics are available under `/actuator/metrics` (admin-only access as defined above), with bounded low-cardinality tags only.

## 6) Future metrics (preparatory audit)

### Current instrumentation-friendly points in code

The following points are already good candidates for future low-risk metric increments:

- **Checkout flow (`OrderServiceImpl.placeOrderFromCart`)**
  - attempt marker at checkout start,
  - success marker after order + payment intent response,
  - failure marker through existing exception path (`BusinessException` / fallback `Exception`).
- **Payment intent initialization (`PaymentServiceImpl.createPaymentIntent`)**
  - success marker for new Stripe PaymentIntent,
  - success marker for idempotent reuse of existing provider intent,
  - failure marker for Stripe/runtime errors mapped to `PaymentProcessingException`.
- **Stripe webhook handling (`PaymentServiceImpl.handleWebhook`)**
  - received marker by stable `eventType`,
  - duplicate/ignored marker when registrar detects replay,
  - handled success marker for supported event types,
  - failure marker for invalid signature/payload and processing failures.
- **Global business errors (`GlobalExceptionHandler.handleBusinessException`)**
  - counter by stable `errorCode` and status class (4xx/5xx),
  - no request-unique identifiers in tags.

### Safe tagging rules for future metrics

When adding meters in a next step, keep tags bounded and low-cardinality:

- **Allowed examples**:
  - `result=success|failure|duplicate|reused`,
  - `flow=checkout|payment_intent|stripe_webhook`,
  - `event_type=payment_intent.succeeded|payment_intent.payment_failed|other` (small controlled set),
  - `error_code=<stable business code>` from `BusinessException`.
- **Forbidden examples** (high cardinality / sensitive):
  - `userId`, `orderId`, `email`, `paymentId`, Stripe intent id, raw exception messages.

### Minimal implementation sequence (future PRs)

1. Add only a **small number of counters** via existing Micrometer API already present in Spring Boot Actuator.
2. Start with **one module path at a time**:
   - PR-1: checkout + payment-intent counters,
   - PR-2: webhook counters,
   - PR-3: business exception counters by `errorCode`.
3. Keep naming stable and explicit, e.g.:
   - `shop.checkout.attempts`,
   - `shop.checkout.completed`,
   - `shop.checkout.failed`,
   - `shop.payment_intent.created`,
   - `shop.payment_intent.reused`,
   - `shop.payment_intent.failed`,
   - `shop.webhook.received`,
   - `shop.webhook.processed`,
   - `shop.webhook.duplicate`,
   - `shop.webhook.failed`,
   - `shop.business_exception.total`.
4. Validate through `/actuator/metrics` (admin-only) before any external scraping/export.

### What we intentionally do NOT add in this preparatory stage

- no Prometheus registry,
- no Grafana or dashboards,
- no deployment/CI/container changes,
- no endpoint contract changes,
- no request/response body logging,
- no high-cardinality tags.

This keeps the change review-safe and aligned with incremental enterprise hardening.
