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

## Outbox processor transaction isolation review

This section records the current transaction model and a safe implementation direction for isolating outbox event failures. It is a design finding only: it does not change processor runtime behavior, schema, retry timing, event payloads, handler registration, manual requeue behavior, notification delivery, or admin APIs.

### Current transaction boundary

`OutboxEventPoller` is the scheduled coordinator. It has no `@Transactional` boundary of its own; when processing is enabled it calls `OutboxEventProcessor.processPendingBatch(properties.batchSize())` from the scheduler thread.

`OutboxEventProcessor.processPendingBatch(...)` is currently annotated with Spring's default `@Transactional`, so one transaction covers the whole batch: selecting due `PENDING` rows with `FOR UPDATE SKIP LOCKED`, invoking every handler, marking successful events `PROCESSED`, scheduling retryable failures as delayed `PENDING`, and marking terminal failures `DEAD_LETTER`.

`OutboxEventRepository.findPendingBatchForUpdate(...)` uses a native PostgreSQL query that selects only due `PENDING` events, orders them deterministically, applies the requested limit, and locks the selected rows with `FOR UPDATE SKIP LOCKED`. Those locks are held until the surrounding processor transaction commits or rolls back.

`OrderPlacedNotificationHandler` is not transactional itself. For supported `OrderPlaced` version `1` events with valid payloads it delegates to `NotificationService.createOrderPlacedNotification(...)` and passes the outbox event id as `sourceEventId`.

`NotificationService.createOrderPlacedNotification(...)` is annotated with default `@Transactional`. Because the handler calls it while the processor transaction is already active, it participates in the processor transaction with propagation `REQUIRED`; it does not create an independent transaction. Its lookup by `sourceEventId` and any inserted notification are committed or rolled back together with all other outbox state changes in the current batch.

### Confirmed behavior versus inferred risk

Confirmed from the current code and Spring transaction defaults:

- The batch is processed in a single transaction owned by `OutboxEventProcessor.processPendingBatch(...)`.
- `NotificationService` normally joins that transaction rather than isolating notification creation from outbox state updates.
- `FOR UPDATE SKIP LOCKED` prevents other concurrent pollers from selecting the locked rows while the batch transaction is open.
- The existing unit tests use mocks and entity state assertions; they verify in-memory retry/dead-letter transitions but do not exercise a real Spring transaction manager, Hibernate flush behavior, or rollback-only commit semantics.

Inferred risk from Spring transaction semantics:

- If a runtime exception escapes a transactional dependency that joined the processor transaction, the dependency's transaction interceptor can mark the shared transaction rollback-only before the processor catches the exception.
- The processor may then continue looping and return a result object with processed and failed counts, but commit can fail later with an `UnexpectedRollbackException` because the transaction was already marked rollback-only.
- If commit rolls back, the failing event's scheduled retry or dead-letter update rolls back, previously processed outbox events in the same batch roll back to their prior state, and notifications created earlier in the same batch roll back too.
- Row locks remain held for the duration of the batch transaction and are released only when the final commit or rollback occurs. A rollback makes those events eligible for later selection again if they are still due `PENDING`.
- The result returned from `processPendingBatch(...)` is only reliable if the surrounding transaction actually commits. If the method return is followed by an `UnexpectedRollbackException`, callers may not observe the returned result at all.

This risk is most relevant for exceptions thrown after entering a transactional handler dependency such as `NotificationService`, especially persistence failures raised during flush. Non-transactional handler validation failures thrown before calling a transactional dependency, such as unsupported event versions or invalid payloads, are caught by the processor without that dependency interceptor marking the processor transaction rollback-only.

### Idempotency review for notification creation

Notification creation has two protections tied to `sourceEventId`:

- `NotificationService` first looks up an existing notification by the outbox event id and returns it instead of inserting another notification.
- The database has a partial unique index on `notifications(source_event_id) WHERE source_event_id IS NOT NULL`, so concurrent or repeated inserts for the same source outbox event cannot both commit.

These protections cover duplicate processing of the same outbox event after crashes, retries, manual requeues, or concurrent races where the same event is attempted more than once. They also protect against duplicate notification records when a notification was committed but the outbox event was later retried.

They do not protect against duplicates across different outbox event ids for the same order, side effects that occur outside the database after a notification is later delivered, duplicate rows when `sourceEventId` is null, or duplicate records created by a future handler that does not pass the source outbox event id. This protection must not be removed or weakened when changing transaction isolation.

### Evaluated isolation options

| Option | Failed-event blast radius | Lock duration and multi-instance behavior | Crash recovery and at-least-once delivery | Failure policy interactions | Migration impact | Complexity, tests, and operational risks |
| --- | --- | --- | --- | --- | --- | --- |
| Keep one batch transaction | A rollback-only marker or commit failure can roll back every event and notification in the batch. | Locks every selected row until the whole batch finishes; other pollers skip the batch rows and may process later rows. | Rollback returns events to prior due `PENDING` state, so they may be retried without recorded failure state. At-least-once is preserved, but duplicate attempts become less visible. | Unknown event types and non-retryable failures are updated in memory, but their persisted retry/dead-letter state is lost if the transaction rolls back. Manual requeue is unchanged. | None. | Lowest implementation cost but highest isolation risk. Existing mock tests are insufficient; integration tests would need real transactions and rollback-only scenarios. |
| Add `REQUIRES_NEW` only around handler-side services such as `NotificationService` | Handler service failures would not mark the processor transaction rollback-only if implemented carefully, but failures from other joined transactional dependencies can still affect the batch. Successful inner transactions can commit side effects even if the processor later rolls back. | Outer batch still locks all selected events until the batch completes. | At-least-once remains, and notification idempotency helps after outer rollback, but committed handler-side effects can diverge from rolled-back outbox state. | Unknown event types never call handler services, so behavior is unchanged. Non-retryable handler validation before the service remains in the outer transaction. Manual requeue unchanged. | None. | Medium complexity and uneven protection. It pushes transaction policy into every handler dependency and can create partial commits that are hard to reason about. |
| Process each event in a separate transaction through a separate Spring bean | One event's transaction rollback does not roll back previously committed events. A rollback still loses that event's failure update unless failure recording is isolated. | If each transaction selects and processes one event with `FOR UPDATE SKIP LOCKED`, locks are held only for that event. Multiple instances can divide work safely. | Crash during one event leaves only that event uncommitted and eligible for retry. At-least-once remains. | Unknown event types and non-retryable failures can be persisted in the per-event transaction when no rollback-only marker occurs; transactional dependency rollback-only still requires separate failure recording. Manual requeue unchanged. | None required. | Moderate complexity. Requires a separate bean to avoid self-invocation bypassing `@Transactional`, plus integration tests for one failed event not rolling back another. |
| Non-transactional coordinator with candidate event ID selection, per-event lock/recheck, per-event processing transaction, and separate failure-recording transaction | Best failure isolation without a schema change. One event cannot roll back another. If the handler transaction rolls back, failure state is written in a new transaction. | Candidate ID selection can be non-locking or short-lived. Each event transaction re-locks and rechecks status/due time with `FOR UPDATE SKIP LOCKED`, so locks are held per event only. Other instances skip locked rows and can process different rows. | Crash before processing leaves the event `PENDING`. Crash after handler side effects but before marking processed can reattempt the event; `sourceEventId` idempotency protects notification creation. Crash after rollback but before separate failure recording may cause an unrecorded retry, still at-least-once. | Unknown event types should keep the current retry/dead-letter policy in the normal per-event transaction. Non-retryable failures should dead-letter immediately. Manual requeue stays compatible because the per-event transaction rechecks current status and due time. | None required. | Recommended first runtime architecture. Higher than option 3 but still focused. Needs repository methods for candidate IDs and per-event locking, a transactional per-event worker bean, a failure recorder with `REQUIRES_NEW`, and integration tests for rollback-only isolation and concurrent pollers. |
| Introduce a claim or `PROCESSING` state and commit claims before handler execution | Can isolate events if claims and completions are per event, but stale claims introduce a new recovery problem. | Claimed rows are not locked during handler execution, improving lock duration. Multi-instance behavior shifts from row locks to claim ownership and stale-claim expiry. | Requires timeout/reaper semantics for crashed processors, metrics for stuck `PROCESSING`, and rules for manual requeue of stuck claims. At-least-once depends on robust claim recovery. | Unknown event types and non-retryable failures need clear transitions from `PROCESSING` to terminal or retry states. Manual requeue must decide whether `PROCESSING` is eligible. | Requires schema/status changes and likely admin/API documentation updates. Not allowed in this review task. | Higher complexity and operational surface. Useful later only if handler execution becomes long enough that row lock duration is unacceptable. |
| Nested transactions/savepoints | A savepoint may isolate some database work inside a transaction, but support is limited and easy to misuse with JPA. It does not reliably isolate all handler-side rollback-only behavior. | Outer batch transaction still holds all selected row locks until final commit. | Outer transaction rollback still loses all batch work. Savepoints do not solve crash recovery or committed external side effects. | Policies remain coupled to outer transaction success. Manual requeue unchanged. | None. | Not recommended. Spring `PROPAGATION_NESTED` depends on JDBC savepoints and transaction manager support, and JPA/Hibernate persistence context state after savepoint rollback is difficult to keep consistent. |

### Recommended architecture and staged implementation plan

The smallest safe first implementation PR should use per-event failure isolation without adding a `PROCESSING` status or any migration. A schema change is not required for the first step.

Recommended shape:

1. Keep `OutboxEventPoller` as a thin scheduled entry point and make the batch-level coordinator non-transactional.
2. Select candidate due `PENDING` event ids in deterministic order with a limited query. The candidate selection does not need to lock rows for handler execution.
3. For each candidate id, call a separate Spring bean method annotated with `@Transactional`. That method should lock and recheck exactly one event using `FOR UPDATE SKIP LOCKED` plus the current `PENDING`/due predicate. If the row is already locked, no longer pending, or not due because another instance processed/requeued it, skip it.
4. Inside the per-event transaction, keep the current external policy: success marks `PROCESSED`; retryable failure schedules the next `PENDING` attempt; exhausted retry marks `DEAD_LETTER`; non-retryable failure marks `DEAD_LETTER` immediately; unknown event types retain the current retry/dead-letter policy.
5. If a handler dependency marks the per-event transaction rollback-only or the per-event transaction otherwise rolls back, catch that outcome at the non-transactional coordinator boundary and persist the retry/dead-letter state in a separate transaction, preferably through a dedicated failure recorder using `REQUIRES_NEW`. The failure recorder must re-lock and recheck the event before updating it so concurrent manual requeue or another processor does not get overwritten.
6. Preserve `sourceEventId` lookup and the unique index for notification idempotency. This is necessary because per-event isolation intentionally keeps at-least-once delivery semantics: a crash or rollback can cause a later repeat attempt.

This design lets concurrent pollers safely select and lock individual events without `PROCESSING`: each worker re-locks a single current due `PENDING` row with `FOR UPDATE SKIP LOCKED`; PostgreSQL gives the row to at most one transaction at a time; later transactions recheck state before applying transitions. The main tradeoff is that an event is locked while its handler runs. That is acceptable for the current notification use case and simpler than introducing claim recovery. A later PR can evaluate `PROCESSING` only if lock duration becomes a measured operational problem.

Suggested staged plan:

1. Documentation and verification PR: record this transaction-boundary review and, optionally, add a focused integration test that demonstrates current rollback-only risk without committing a failing test.
2. Isolation PR: introduce a non-transactional coordinator, per-event transactional worker bean, single-event lock/recheck repository method, and separate failure recorder transaction. Preserve all status policies and public APIs.
3. Test hardening PR: add integration tests proving one event failure cannot roll back another event, failure state persists after a rolled-back handler attempt, concurrent processors skip locked single events, unknown event policy remains unchanged, non-retryable failures dead-letter immediately, and manual requeue is not overwritten by stale failure recording.
4. Operational follow-up PR, only if needed: improve metrics/docs around per-event processing duration and lock contention. Do not add `PROCESSING` unless there is evidence that lock duration is an operational issue.
