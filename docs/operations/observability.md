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

Configured actuator web exposure includes `health`, `info`, `metrics`, and `prometheus`.

| Endpoint | Access | Purpose |
| --- | --- | --- |
| `/actuator/health` | Public | Health status. Details are shown when authorized. |
| `/actuator/info` | Admin | Application info. |
| `/actuator/metrics` | Admin | Metrics index and individual meter lookup. |
| `/actuator/prometheus` | Admin | Prometheus scrape endpoint provided by the Prometheus registry. |

## Application metrics

Current custom Micrometer counters:

| Metric | Producer | Tags | Current results/status values |
| --- | --- | --- | --- |
| `shop.checkout.total` | Checkout processor | `result` | `attempt`, `success`, `failure` |
| `shop.payment_intent.total` | Payment service | `result` | `created`, `reused`, `failed` |
| `shop.webhook.total` | Payment service | `result` | `received`, `processed`, `ignored`, `duplicate`, `failed` |
| `shop.business_exception.total` | Global exception handler | `error_code`, `status_class` | `status_class` is `4xx`, `5xx`, or `other` |

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
