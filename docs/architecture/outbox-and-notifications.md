# Outbox and notifications

The current implementation is an internal, DB-backed outbox baseline. It does not use an external broker and should be understood as a simple consistency mechanism within this application.

## Checkout event recording

During checkout, `OrderCheckoutProcessor` creates the order, creates the Stripe payment record, and records an order placed outbox event through `OrderOutboxEventRecorder` in the same transactional flow. The recorder writes the currently supported `OrderPlaced` event version, `1`, explicitly.

The recorded event uses:

| Field | Current value |
| --- | --- |
| Aggregate type | `Order` |
| Event type | `OrderPlaced` |
| Event version | `1` |
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

The poller keeps using `fixed-delay` as its scheduling interval. The processor selects only due `PENDING` outbox events: events with `next_attempt_at` unset or not later than the current database timestamp. The processor routes by exact `event_type`; version checks remain handler-specific. Every handler must declare one nonblank exact event type without leading or trailing whitespace, and handler event types must be unique. Duplicate or invalid handler declarations fail processor construction and therefore application startup instead of selecting one handler automatically. Retryable handler failures remain `PENDING`, increment attempts, store `last_error` and `last_attempt_at`, and set `next_attempt_at` to the next scheduled processor attempt. Failures that reach `max-attempts` are marked `DEAD_LETTER`, keep the terminal error details, store a dead-letter reason, and clear `next_attempt_at`. Handlers may signal deterministic contract failures as non-retryable; the processor marks those events `DEAD_LETTER` immediately, records one attempt, stores the handler error in `last_error`, uses the `Non-retryable processing failure` dead-letter reason, and does not schedule another retry. Unknown event types retain the existing retry/dead-letter policy with an explanatory error. Existing `FAILED` events are not automatically retried by the processor.

## Notification handling

`OrderPlacedNotificationHandler` handles `OrderPlaced` outbox events using the shared event type constant and `OrderPlacedEventPayload` contract. It accepts only `OrderPlaced` event version `1`, rejects unsupported versions before payload parsing or notification creation, validates the version-1 payload fields it needs, and delegates to `NotificationService`. Unsupported versions, malformed JSON, and missing required version-1 payload fields are deterministic contract failures and are dead-lettered immediately. Transient failures from notification creation continue to use the configured `retry-delay` and `max-attempts` policy.

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

## Event metadata/versioning transition plan

The current outbox contract intentionally stores routing and version metadata outside the JSON payload: `outbox_events.event_type` remains the handler routing source, `outbox_events.event_version` stores queryable positive version metadata, and `outbox_events.payload` contains the raw event-specific payload. Legacy rows and payloads without metadata correspond to implicit version `1`; the schema default stores existing and new rows as version `1`. Existing rows therefore contain raw `OrderPlacedEventPayload` JSON, not an envelope, and new `OrderPlaced` events keep that non-enveloped payload shape. The current supported `OrderPlaced` event version is `1`: the recorder writes version `1` explicitly, and the notification handler accepts only version `1`.

### Evaluated options

| Option | Compatibility and migration impact | Producer changes | Handler changes | Test coverage | Operational/admin API impact | Risks |
| --- | --- | --- | --- | --- | --- | --- |
| Keep `event_type` as the routing source and add optional version metadata only to future raw payloads | No table migration. Existing rows remain readable if added payload fields are optional/ignored by current deserialization. | Add optional metadata fields to future payload records only when needed. | Handlers keep reading the typed payload and default missing metadata to version 1. | Serialization tests proving current raw payload shape remains accepted; handler tests for missing metadata default. | Existing list/detail/filter/requeue APIs continue to work because routing fields stay unchanged. | Metadata is duplicated per payload type and cannot be queried efficiently without JSON inspection. |
| Add nullable `event_version` column to `outbox_events` | Small additive migration if nullable or defaulted. Existing rows can be interpreted as version 1. | `OutboxEvent.pending(...)` and recorders set version for new events after the entity is extended. | Handlers can branch on `event.getEventVersion()` while still parsing raw payloads. | Migration test for default/nullable behavior; repository/entity mapper tests; processor/handler tests for null/default version 1. | Admin DTOs may later expose/filter version, but the first migration can avoid API changes. | Requires schema/entity/API sequencing discipline; adding NOT NULL without a safe default/backfill would break existing rows. |
| Introduce `EventEnvelope<T>` JSON wrapper in `payload` | Breaking if handlers switch directly to envelope parsing, because existing rows are raw payloads. No schema migration, but JSON shape changes. | Recorders write `{metadata..., payload:{...}}` instead of raw payload. | Handlers must unwrap envelope before parsing the typed payload. | Dual-shape parser tests for raw and enveloped JSON; producer tests for the new shape; processor retry tests for parse failures. | Admin detail payload display changes shape for new rows, which can surprise operators and clients that inspect payload. | Highest compatibility risk unless dual-read is implemented before any producer writes envelopes. |
| Support both legacy raw payloads and new enveloped payloads during a transition period | No immediate migration required. Backward compatible if introduced as read-only support first. | Initially none; later producers can opt in event-by-event. | Add an envelope-aware parser that detects wrapper shape and falls back to raw typed payload parsing. | Handler/parser tests for legacy raw payloads, valid envelopes, malformed envelopes, unknown/unsupported versions, and required-field validation after unwrapping. | Admin APIs can remain unchanged, but documentation should state that payload may be raw or enveloped only after producer opt-in. | More parser complexity; ambiguous wrapper field names must be reserved and validated consistently. |

### Recommended sequence

1. Preserve `event_type` as the authoritative routing source. The processor already dispatches by the `event_type` column, so routing should not move into JSON metadata.
2. First implementation PR: add tests and documentation that lock in legacy raw `OrderPlacedEventPayload` support and define version `1` as the implicit version for rows without metadata. Do not change the producer payload shape, processor behavior, or schema in that PR.
3. Second implementation PR: add the defaulted `event_version` column and map it on `OutboxEvent`, with version `1` as the default for existing and newly recorded events. Keep admin API exposure optional and separate from the migration.
4. Only after dual-read support exists, consider an `EventEnvelope<T>` for new event types or explicitly opted-in versions. Envelope writes should be introduced per event type and accompanied by handler tests proving both raw legacy and enveloped rows process successfully.

The current safe slice adds queryable version metadata while keeping `event_type` routing and the raw payload contract unchanged.
