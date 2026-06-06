# Outbox and notifications

The current implementation is an internal, DB-backed outbox baseline. It does not use an external broker and should be understood as a simple consistency mechanism within this application.

## Checkout event recording

During checkout, `OrderCheckoutProcessor` creates the order, creates the Stripe payment record, and records an order placed outbox event through `OrderOutboxEventRecorder` in the same transactional flow.

The recorded event uses:

| Field | Current value |
| --- | --- |
| Aggregate type | `Order` |
| Event type | `OrderPlaced` |
| Payload | JSON containing order id, user id/email, status, total amount, created time, and item snapshots. |
| Initial status | `PENDING` |

## Outbox processing

| Component | Responsibility |
| --- | --- |
| `OutboxEventPoller` | Scheduled entry point. Runs with `fixedDelayString = ${app.outbox.processing.fixed-delay:PT10S}` and returns immediately when processing is disabled. |
| `OutboxProcessingProperties` | Binds `app.outbox.processing.*` with defaults. |
| `OutboxEventProcessor` | Loads a pending batch, selects a handler by event type, marks events processed or failed, and stores failure details. |
| `OutboxEventHandler` | Handler interface implemented by event-specific consumers. |

Current property defaults:

| Property | Default |
| --- | --- |
| `app.outbox.processing.enabled` | `false` |
| `app.outbox.processing.batch-size` | `25` |
| `app.outbox.processing.fixed-delay` | `PT10S` |

Failed outbox events are marked `FAILED`, increment attempts, and store `last_error`. Unknown event types also fail with an explanatory error.

## Notification handling

`OrderPlacedNotificationHandler` handles `OrderPlaced` outbox events. It validates the payload fields it needs and delegates to `NotificationService`.

`NotificationService` creates a notification record with:

| Field | Current value |
| --- | --- |
| Type | `ORDER_PLACED_EMAIL` |
| Recipient | Order user's email from the outbox payload. |
| Source event | Outbox event id. |
| Initial status | `PENDING` |

## Notification delivery

Notification delivery is represented by a `NotificationSender` abstraction.

- `NotificationDeliveryProcessor` loads pending notifications in batches, calls `NotificationSender`, marks successful notifications `SENT`, and marks failed notifications `FAILED` with `last_error`.
- `NoopNotificationSender` is the current sender implementation. It logs that delivery is skipped and does not call an external email/SMS provider.
- There is currently no scheduled notification-delivery poller documented in production behavior; delivery is available as an internal processor component.
