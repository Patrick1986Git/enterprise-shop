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
- SMTP connection, socket-read, and socket-write waits default to finite 30-second values. Configure them with `app.notification.smtp.connection-timeout`, `app.notification.smtp.read-timeout`, and `app.notification.smtp.write-timeout`; production exposes the corresponding `NOTIFICATION_SMTP_*_TIMEOUT` environment variables. Values must be positive, supported by Jakarta Mail's millisecond integer properties, and individually shorter than `app.notification.delivery.claim-duration` (five minutes by default).
- These timeouts bound individual Jakarta Mail socket operations; they do not establish a strict wall-clock bound for the complete SMTP transaction. Keeping each timeout below the claim lease is a startup safety check, not proof that a send always finishes before lease expiry. Operators must monitor timeout/failure rates and size the lease for their SMTP exchange behavior.
- Delivery remains at-least-once at the provider boundary. A provider can accept a message before a timeout or before local token-guarded finalization fails, and expired-claim recovery can then cause another external send. Claim tokens fence database finalization only; neither the lease nor socket timeouts provide exactly-once email delivery.

Example local SMTP notification configuration:

```properties
app.notification.delivery.enabled=true
app.notification.smtp.enabled=true
app.notification.smtp.from=no-reply@example.com
spring.mail.host=localhost
spring.mail.port=1025
app.notification.smtp.connection-timeout=PT30S
app.notification.smtp.read-timeout=PT30S
app.notification.smtp.write-timeout=PT30S
```

The default Compose stack deliberately omits `SPRING_MAIL_HOST` and `SPRING_MAIL_PORT`, so disabled notification SMTP does not activate Spring Mail or make application health depend on an SMTP server. To opt in under Compose, supply `APP_NOTIFICATION_SMTP_ENABLED=true` together with Spring Boot's standard `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, and any required authentication/TLS variables in a deployment-specific Compose override. Do not add a placeholder mail host to the default stack.

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

## Outbox processor transaction isolation

`OutboxEventPoller` remains the thin scheduled entry point. It has no transaction boundary of its own; when processing is enabled it calls `OutboxEventProcessor.processPendingBatch(properties.batchSize())` from the scheduler thread.

`OutboxEventProcessor.processPendingBatch(...)` is now a non-transactional batch coordinator. It selects a deterministic, limited list of due `PENDING` event ids ordered by `next_attempt_at ASC NULLS FIRST`, `created_at ASC`, and `id ASC`. Candidate selection does not lock rows for the whole batch. The coordinator invokes a separate Spring bean once per candidate id and aggregates only committed `PROCESSED` and failure-recording outcomes into `OutboxEventProcessingResult`; skipped candidates are not counted.

`OutboxEventTransactionalWorker` owns the default transactional worker boundary for one event. It re-locks a single due `PENDING` row by id with `FOR UPDATE SKIP LOCKED`, rechecks eligibility in the database, resolves the handler by exact `eventType`, invokes the handler, and marks the event `PROCESSED` on success. Handler writes such as notification creation and the outbox success transition commit together in this one-event transaction. If the row is missing, locked by another transaction, already processed, no longer pending, or no longer due, the worker skips the candidate.

Multiple coordinator instances may select overlapping candidate ids because candidate selection is intentionally lock-free. Correctness is enforced later by the single-event worker lock and due-`PENDING` eligibility recheck: a locked, already processed, no-longer-pending, or no-longer-due candidate is skipped and is not reported as processed or failed. Candidate overlap can reduce effective batch utilization, but it does not allow duplicate committed processing or overwrite a committed `PROCESSED` transition.

Handler exceptions, persistence exceptions, and transaction commit failures are allowed to escape the worker so that the one-event transaction rolls back completely. The coordinator then calls `OutboxEventFailureRecorder`, which uses `REQUIRES_NEW`, re-locks and rechecks the same due `PENDING` row, and records retry or dead-letter state independently. Non-retryable failures still become `DEAD_LETTER` immediately with the existing `Non-retryable processing failure` reason. Retryable failures keep the existing attempts, retry delay, max-attempt, and `Max attempts exceeded` behavior.

Locks are therefore held for one event attempt rather than for the entire poller batch. One failed transactional handler dependency can roll back only that event's worker attempt; it cannot roll back successful events that already committed in the same coordinator batch. If another processor changes the row before failure recording, the recorder skips rather than overwriting the newer state.

Delivery remains at-least-once. A committed handler side effect can still be followed by a retry after crashes or uncertain outcomes, so notification creation must continue to use the outbox event id as `sourceEventId` and rely on the existing idempotency lookup plus partial unique index.

### Idempotency review for notification creation

Notification creation has two protections tied to `sourceEventId`:

- `NotificationService` first looks up an existing notification by the outbox event id and returns it instead of inserting another notification.
- The database has a partial unique index on `notifications(source_event_id) WHERE source_event_id IS NOT NULL`, so concurrent or repeated inserts for the same source outbox event cannot both commit.

These protections cover duplicate processing of the same outbox event after crashes, retries, manual requeues, or concurrent races where the same event is attempted more than once. They also protect against duplicate notification records when a notification was committed but the outbox event was later retried.

They do not protect against duplicates across different outbox event ids for the same order, side effects that occur outside the database after a notification is later delivered, duplicate rows when `sourceEventId` is null, or duplicate records created by a future handler that does not pass the source outbox event id. This protection must not be removed or weakened when changing transaction isolation.
## Notification delivery transaction contract

Delivery uses three explicit phases. For each item, a short transaction locks one eligible
row with `FOR UPDATE SKIP LOCKED`, assigns a unique claim token and lease, increments
the attempt count, and commits. The configured batch size limits how many of these
per-item cycles a poll processes; later items are not claimed while an earlier send is
still running. The sender then performs SMTP or other external I/O
without a database transaction or row lock. A separate short transaction changes the
row to `SENT`, or durably records a retry/terminal failure, only when the claim token
still owns the row.

An unexpired claim cannot be stolen. An expired claim is eligible for a new worker;
the new token prevents the stale worker from finalizing over that recovery attempt.
An expired final allowed attempt becomes `FAILED`. Operators may requeue only
`FAILED` notifications, so an active claim cannot be mutated concurrently.

The guarantee is at-least-once attempted delivery with bounded retries and
application-level prevention of concurrent delivery for a valid lease. SMTP has no
provider-side idempotency contract: if the provider accepts a message and the process
fails before local success finalization, lease recovery can send it again. The system
therefore does not promise exactly-once external delivery.

The default claim lease is five minutes. The application does not currently configure
JavaMail connection, read, or write timeouts, so an SMTP operation can outlive that
lease. In that case recovery may start a new send while the stale sender is still
running; token ownership prevents the stale worker from changing database state but
cannot prevent or undo either external SMTP side effect. Operators must configure
bounded provider timeouts below the claim lease when enabling SMTP.
