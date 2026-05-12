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
