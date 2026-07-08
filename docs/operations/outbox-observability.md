# Admin outbox observability

## Purpose

The admin outbox observability endpoints help administrators and admin UI consumers inspect transactional outbox health in this modular monolith. They provide read-focused visibility into event processing state, failed processing attempts, scheduled retry timing, dead-letter state, requeue history, and admin action logs without changing the outbox architecture or introducing external brokers.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/admin/outbox-events/summary` | Return aggregate outbox health indicators and operational thresholds. |
| `GET` | `/api/v1/admin/outbox-events` | List outbox events with filters, pagination, and sorting. |
| `GET` | `/api/v1/admin/outbox-events/{id}` | Inspect one outbox event in detail. |
| `POST` | `/api/v1/admin/outbox-events/{id}/requeue` | Requeue a failed or dead-lettered outbox event for processing. |
| `GET` | `/api/v1/admin/outbox-events/{id}/actions` | List admin action logs for one outbox event. |
| `GET` | `/api/v1/admin/outbox-event-actions` | Search admin outbox action logs globally. |

## Summary indicators

`GET /api/v1/admin/outbox-events/summary` returns counts and timestamps that are useful for a quick health check:

- `pendingCount`, `processedCount`, `failedCount`, `deadLetterCount`, and `totalCount`: event counts by processing state and overall total.
- `requeuedEventCount`: number of events that have been manually requeued at least once.
- `totalRequeueCount`: total number of manual requeue actions recorded across events.
- `stalePendingCount`: number of `PENDING` events older than `staleThresholdMinutes` by `createdAt`.
- `staleFailedCount`: number of `FAILED` events whose `lastAttemptAt` is older than `staleThresholdMinutes`.
- `highAttemptFailedCount`: number of `FAILED` events with `attempts >= highFailedAttemptsThreshold`.
- `staleThresholdMinutes`: staleness threshold used for stale pending and stale failed indicators. The current value is `15`.
- `highFailedAttemptsThreshold`: attempt threshold used for high-attempt failed indicators. The current value is `3`.
- `oldestPendingCreatedAt`: oldest creation timestamp among pending events.
- `newestFailedCreatedAt`: newest creation timestamp among failed events.
- `newestAttemptAt`: newest processing-attempt timestamp across outbox events.
- `newestProcessedAttemptAt`: newest attempt timestamp among processed events.
- `newestFailedAttemptAt`: newest attempt timestamp among failed events.
- `oldestDeadLetterCreatedAt`: oldest creation timestamp among dead-lettered events.
- `newestDeadLetterAttemptAt`: newest attempt timestamp among dead-lettered events.

## Problem type filter

`GET /api/v1/admin/outbox-events` supports `problemType` for common operational investigations:

- `STALE_PENDING`: returns `PENDING` events older than the stale threshold by `createdAt`.
- `STALE_FAILED`: returns `FAILED` events whose `lastAttemptAt` is older than the stale threshold.
- `HIGH_ATTEMPT_FAILED`: returns `FAILED` events with `attempts >= highFailedAttemptsThreshold`.
- `DEAD_LETTER`: returns `DEAD_LETTER` events that reached terminal retry handling and require operational review.

The problem type filter can be combined with other list filters such as `aggregateType`, `eventType`, `attemptsMin`, `attemptsMax`, pagination, and sorting.

## Supported event list filters

`GET /api/v1/admin/outbox-events` supports `status`, `aggregateType`, `aggregateId`, `eventType`, `lastErrorContains`, `createdFrom`, `createdTo`, `processedFrom`, `processedTo`, `lastAttemptFrom`, `lastAttemptTo`, `nextAttemptFrom`, `nextAttemptTo`, `attemptsMin`, `attemptsMax`, `requeuedOnly`, `problemType`, pagination, and sorting. `aggregateType`, `eventType`, and `lastErrorContains` use case-insensitive contains matching after trimming and ignore blank values. `aggregateId` is an exact UUID match. `createdFrom`/`createdTo`, `processedFrom`/`processedTo`, `lastAttemptFrom`/`lastAttemptTo`, and `nextAttemptFrom`/`nextAttemptTo` are inclusive timestamp ranges. `attemptsMin` and `attemptsMax` are inclusive numeric bounds. `requeuedOnly=true` returns events with `requeueCount > 0`; omitting it or setting it to `false` does not restrict by requeue count. `problemType` keeps the operational meanings listed above for `STALE_PENDING`, `STALE_FAILED`, `HIGH_ATTEMPT_FAILED`, and `DEAD_LETTER`.

List responses include `nextAttemptAt` so admins can see delayed `PENDING` retry schedules. Detail responses include both `nextAttemptAt` and `deadLetterReason`; `deadLetterReason` explains why a `DEAD_LETTER` event became terminal.

## Common operational queries

The examples below use relative paths and ISO-8601 UTC timestamps. Add the normal admin authentication headers used by the environment.

### List stale pending events

```http
GET /api/v1/admin/outbox-events?problemType=STALE_PENDING&sort=createdAt,asc&size=20
```

Use this when `stalePendingCount` is greater than zero to see the oldest pending events first.

### List stale failed events

```http
GET /api/v1/admin/outbox-events?problemType=STALE_FAILED&sort=lastAttemptAt,asc&size=20
```

Use this when `staleFailedCount` is greater than zero to find failed events that have not been attempted recently.

### List high-attempt failed events

```http
GET /api/v1/admin/outbox-events?problemType=HIGH_ATTEMPT_FAILED&sort=attempts,desc&size=20
```

Use this when `highAttemptFailedCount` is greater than zero to review failed events that have reached the high-attempt threshold.

### List scheduled retry events

```http
GET /api/v1/admin/outbox-events?status=PENDING&nextAttemptFrom=2026-06-21T00:00:00Z&nextAttemptTo=2026-06-21T01:00:00Z&sort=nextAttemptAt,asc&size=20
```

Use `nextAttemptFrom` and `nextAttemptTo` to inspect pending events scheduled for retry in a time window. A future `nextAttemptAt` means the event is pending but not due for the retry poller yet.

### List dead-lettered events

```http
GET /api/v1/admin/outbox-events?problemType=DEAD_LETTER&sort=lastAttemptAt,desc&size=20
```

Use this to review terminal outbox events. `DEAD_LETTER` means retry handling exhausted the configured policy and the event is no longer eligible for scheduled retry processing. Inspect the detail response for `deadLetterReason` before planning follow-up remediation. After remediation, administrators can manually requeue `DEAD_LETTER` events through the existing requeue endpoint.

### Search failures by error text

```http
GET /api/v1/admin/outbox-events?status=FAILED&lastErrorContains=timeout&sort=lastAttemptAt,desc&size=20
```

Use `lastErrorContains` to group failures that share a recognizable error fragment.

### List events processed during a time window

```http
GET /api/v1/admin/outbox-events?status=PROCESSED&processedFrom=2026-06-21T00:00:00Z&processedTo=2026-06-21T01:00:00Z&sort=processedAt,desc&size=50
```

Use `processedFrom` and `processedTo` to inspect events completed during a deployment, incident, or support window.

### List requeued events

```http
GET /api/v1/admin/outbox-events?requeuedOnly=true&sort=lastRequeuedAt,desc&size=20
```

Use this to review events that have been manually requeued at least once.

### Inspect action logs for a specific outbox event

```http
GET /api/v1/admin/outbox-events/11111111-1111-1111-1111-111111111111/actions?sort=createdAt,desc&size=20
```

Use event-specific action logs to audit who requeued an event and when the action was recorded. `REQUEUE` entries include `actorEmail`, `createdAt`, `actionType`, `outboxEventId`, and `details`. For manual requeues, `details` is deterministic: `Manual requeue requested for failed outbox event.`

### Search global outbox action logs

```http
GET /api/v1/admin/outbox-event-actions?actorEmail=admin@example.com&createdFrom=2026-06-21T00:00:00Z&createdTo=2026-06-21T23:59:59Z&sort=createdAt,desc&size=50
```

Use global action log search to inspect admin activity by `actorEmail`, `createdFrom`, `createdTo`, `outboxEventId`, or `actionType`.

## Operational notes

- These endpoints are admin-only.
- Requeue is available for `FAILED` and `DEAD_LETTER` outbox events through `POST /api/v1/admin/outbox-events/{id}/requeue`; `PENDING` and `PROCESSED` events are rejected.
- Manual requeue changes the event back to `PENDING`, clears `lastError`, `nextAttemptAt`, and `deadLetterReason`, increments `requeueCount`, and records `lastRequeuedAt` and `lastRequeuedBy`.
- Requeue records admin action log entries for auditability.
- Action log `details` is informational and makes audit entries self-descriptive for admins and admin UI consumers.
- The documented `REQUEUE` details value does not change outbox processing, retry, requeue eligibility, scheduling, persistence behavior, endpoint paths, DTO shape, or security.
- Delete endpoints, manual status mutation, and retry-now operations are intentionally not part of this API.
- This remains a modular monolith transactional outbox. It does not introduce Kafka, RabbitMQ, external brokers, or microservices.
