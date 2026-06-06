# Error handling

## Error response shape

Errors are serialized as `ApiError`:

| Field | Meaning |
| --- | --- |
| `status` | HTTP status code. |
| `message` | Human-readable, usually localized message. |
| `errorCode` | Machine-readable error code when available. |
| `errors` | Optional validation/detail object. |
| `timestamp` | Server-side error timestamp. |

## Exception mapping

| Failure type | HTTP status | Error code | Detail behavior |
| --- | --- | --- | --- |
| Bean Validation on request bodies (`MethodArgumentNotValidException`) | `400` | `VALIDATION_FAILED` | `errors` maps field names to lists of messages; global errors use `_global`. |
| Constraint violations (`ConstraintViolationException`) | `400` | `VALIDATION_FAILED` | `errors` maps property paths to messages. |
| Invalid path/query parameter type (`MethodArgumentTypeMismatchException`) | `400` | `REQUEST_INVALID` | Includes `parameter` and `expectedType`. |
| Malformed or invalid request body (`HttpMessageNotReadableException`) | `400` | `REQUEST_INVALID` | Distinguishes malformed JSON from invalid body values through message resolution. |
| Missing query/form parameter (`MissingServletRequestParameterException`) | `400` | `REQUEST_INVALID` | Includes `parameter` and `expectedType`. |
| Missing request header (`MissingRequestHeaderException`) | `400` | `REQUEST_INVALID` | Includes header name as `parameter` and expected Java type when available. |
| Optimistic locking conflict (`ObjectOptimisticLockingFailureException`) | `409` | `OPTIMISTIC_LOCK_CONFLICT` | Used for concurrent update conflicts. |
| Access denied (`AccessDeniedException`) | `403` | `ACCESS_DENIED` | Authorization failure after authentication/security evaluation. |
| Endpoint not found (`NoHandlerFoundException`) | `404` | `ENDPOINT_NOT_FOUND` | Includes method/path in the resolved message. |
| `BusinessException` | Exception-defined status | Exception-defined code, or `UNKNOWN_BUSINESS_ERROR` fallback | Message comes from message key/args when present, otherwise exception message/generic message. |
| Unexpected exception | `500` | Not set | Logs full server-side stack trace and hides implementation details from clients. |

## Business exceptions

Domain-specific failures should extend `BusinessException` and define their HTTP status, error code, and message behavior. `GlobalExceptionHandler` does not manually map individual domain exception classes; it maps the shared base type.

## Metrics

Each handled `BusinessException` increments a Micrometer counter:

| Metric | Tags |
| --- | --- |
| `shop.business_exception.total` | `error_code`, `status_class` |

`status_class` is currently `4xx`, `5xx`, or `other`.
