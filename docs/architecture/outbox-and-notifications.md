# Outbox and notifications

The current implementation is an internal, DB-backed outbox baseline. It does not use an external broker and should be understood as a simple consistency mechanism within this application.

## Checkout event recording

During checkout, `OrderCheckoutProcessor` creates the order, creates the Stripe payment record, and records an order placed outbox event through `OrderOutboxEventRecorder` in the same transactional flow.

The recorded event uses:

| Field | Current value |
| --- | --- |
| Aggregate type | `Order` |
| Event type | `OrderPlaced` |
| Payload | `OrderPlacedEventPayload` JSON containing order id, user id/email, status, total amount, created time, and item snapshots. |
| Initial status | `PENDING` |

## Outbox processing

| Component | Responsibility |
| --- | --- |
| `OutboxEventPoller` | Scheduled entry point. Runs with `fixedDelayString = ${app.outbox.processing.fixed-delay:PT10S}` and returns immediately when processing is disabled. |
| `OutboxProcessingProperties` | Binds `app.outbox.processing.*` with defaults. |
| `OutboxEventProcessor` | Loads due pending events, selects a handler by event type, marks events processed, schedules retryable failures, and dead-letters terminal failures. |
| `OutboxEventHandler` | Handler interface implemented by event-specific consumers. |

Current property defaults:

| Property | Default |
| --- | --- |
| `app.outbox.processing.enabled` | `false` |
| `app.outbox.processing.batch-size` | `25` |
| `app.outbox.processing.fixed-delay` | `PT10S` |
| `app.outbox.processing.retry-delay` | `PT1M` |
| `app.outbox.processing.max-attempts` | `3` |

The poller keeps using `fixed-delay` as its scheduling interval. The processor selects only due `PENDING` outbox events: events with `next_attempt_at` unset or not later than the current database timestamp. Retryable handler failures remain `PENDING`, increment attempts, store `last_error` and `last_attempt_at`, and set `next_attempt_at` to the next scheduled processor attempt. Failures that reach `max-attempts` are marked `DEAD_LETTER`, keep the terminal error details, store a dead-letter reason, and clear `next_attempt_at`. Unknown event types follow the same retry/dead-letter policy with an explanatory error. Existing `FAILED` events are not automatically retried by the processor.

## Notification handling

`OrderPlacedNotificationHandler` handles `OrderPlaced` outbox events using the shared event type constant and `OrderPlacedEventPayload` contract. It validates the payload fields it needs and delegates to `NotificationService`.

`NotificationService` creates a notification record with:

| Field | Current value |
| --- | --- |
| Type | `ORDER_PLACED_EMAIL` |
| Recipient | Order user's email from the outbox payload. |
| Source event | Outbox event id. |
| Initial status | `PENDING` |

## Notification delivery

Notification delivery is represented by a `NotificationSender` abstraction.

- `NotificationDeliveryPoller` can periodically invoke `NotificationDeliveryProcessor` when `app.notification.delivery.enabled=true`.
- `NotificationDeliveryProcessor` loads pending notifications in batches, calls `NotificationSender`, marks successful notifications `SENT`, and marks failed notifications `FAILED` with `last_error`.
- `NoopNotificationSender` is the default fallback sender. It logs that delivery is skipped and does not call an external email/SMS provider.
- `SmtpNotificationSender` is an opt-in SMTP adapter. It is registered only when `app.notification.smtp.enabled=true`; SMTP connection details continue to use Spring Boot `spring.mail.*` properties.

Example local SMTP notification configuration:

```properties
app.notification.delivery.enabled=true
app.notification.smtp.enabled=true
app.notification.smtp.from=no-reply@example.com
spring.mail.host=localhost
spring.mail.port=1025
```
