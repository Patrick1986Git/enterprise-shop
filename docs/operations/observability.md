# Observability: request correlation (`X-Request-Id`)

## Purpose
`X-Request-Id` is used as a per-request correlation identifier for HTTP traffic. It enables fast correlation between:
- client reports,
- API responses,
- and server logs.

## Runtime behavior
For every HTTP request, `RequestIdFilter` enforces one stable request identifier:
- if client sends a valid `X-Request-Id`, the value is reused,
- if header is missing, blank, too long, or invalid, server generates a UUID,
- resolved identifier is added to response header `X-Request-Id`.

## MDC log correlation
Resolved request id is stored in MDC key `requestId` for request scope, so all logs produced during request handling can be correlated.

After request completion, MDC is always cleared to prevent leakage across requests.

## Response visibility
Clients always receive the resolved id in `X-Request-Id` response header, including error responses handled by `GlobalExceptionHandler`.

## Debugging workflow
When debugging incidents:
1. read `X-Request-Id` from client-visible response,
2. search application logs for `requestId=<value>`,
3. inspect all related entries across controller/service/security/error layers.
