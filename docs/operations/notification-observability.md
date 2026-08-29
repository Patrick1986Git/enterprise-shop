# Admin notification observability

## Purpose

The admin notification observability endpoints help administrators and admin UI consumers inspect notification delivery health in this modular monolith. They provide practical visibility into pending and scheduled delivery, failed notifications, requeue history, and admin action logs without changing notification processing behavior.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/admin/notifications` | List notifications with filters, pagination, and sorting. |
| `GET` | `/api/v1/admin/notifications/summary` | Return aggregate notification delivery and requeue indicators. |
| `GET` | `/api/v1/admin/notifications/{id}` | Inspect one notification in detail. |
| `POST` | `/api/v1/admin/notifications/{id}/requeue` | Requeue a notification when the current notification service rules allow it. |
| `GET` | `/api/v1/admin/notifications/{id}/actions` | List admin action logs for one notification. |
| `GET` | `/api/v1/admin/notification-actions` | Search admin notification action logs globally. |

## Summary indicators

`GET /api/v1/admin/notifications/summary` returns counts that are useful for a quick delivery health check:

- `pendingCount`: number of notifications currently waiting for delivery.
- `sentCount`: number of notifications that have been sent.
- `failedCount`: number of notifications currently in a failed state.
- `duePendingCount`: number of pending notifications that are due for delivery now.
- `scheduledPendingCount`: number of pending notifications scheduled for future delivery.
- `requeuedNotificationCount`: number of notifications that have been manually requeued at least once.
- `totalRequeueCount`: total number of manual requeue actions recorded across notifications.

## Supported notification list filters

`GET /api/v1/admin/notifications` supports `status`, `deliveryState`, `sourceEventId`, `type`, `recipient`, `lastErrorContains`, `lastRequeuedBy`, `requeuedOnly`, `attemptsMin`, `attemptsMax`, `lastAttemptFrom`, `lastAttemptTo`, `lastRequeuedFrom`, `lastRequeuedTo`, `createdFrom`, `createdTo`, `sentFrom`, `sentTo`, pagination, and sorting. `status` and `deliveryState` are enum filters. `sourceEventId` is an exact UUID match. `type` uses trimmed exact matching. `recipient`, `lastErrorContains`, and `lastRequeuedBy` use trimmed case-insensitive contains matching and ignore blank values. `requeuedOnly=true` returns notifications with `requeueCount > 0`; omitting it or setting it to `false` does not restrict by requeue count. `attemptsMin` and `attemptsMax` are inclusive numeric bounds. `lastAttemptFrom`/`lastAttemptTo`, `lastRequeuedFrom`/`lastRequeuedTo`, `createdFrom`/`createdTo`, and `sentFrom`/`sentTo` are inclusive timestamp ranges. `deliveryState=DUE_PENDING` means pending notifications with `nextAttemptAt` null or due now. `deliveryState=SCHEDULED_PENDING` means pending notifications scheduled for later.

## Common operational queries

`GET /api/v1/admin/notification-actions` supports `notificationId`, `actionType`, `actorEmail`, `createdFrom`, `createdTo`, pagination, and sorting.

The examples below use relative paths and ISO-8601 UTC timestamps. Add the normal admin authentication headers used by the environment.

### List failed notifications

```http
GET /api/v1/admin/notifications?status=FAILED&sort=createdAt,desc&size=20
```

Use this when `failedCount` is greater than zero to review the most recent failures first.

### List pending notifications

```http
GET /api/v1/admin/notifications?status=PENDING&sort=scheduledAt,asc&size=20
```

Use this to inspect pending delivery work, including notifications that may be due soon.

### Delivery state filtering

Use `deliveryState=DUE_PENDING` to list the concrete pending notifications behind `duePendingCount`. Use `deliveryState=SCHEDULED_PENDING` to list the concrete pending notifications behind `scheduledPendingCount`.

```http
GET /api/v1/admin/notifications?deliveryState=DUE_PENDING
```

```http
GET /api/v1/admin/notifications?deliveryState=SCHEDULED_PENDING
```

```http
GET /api/v1/admin/notifications?deliveryState=DUE_PENDING&recipient=customer@example.com
```

```http
GET /api/v1/admin/notifications?deliveryState=SCHEDULED_PENDING&type=ORDER_PLACED_EMAIL
```

### Created date filtering

Use `createdFrom` and `createdTo` to filter by `createdAt`: `createdFrom` matches notifications created at or after the timestamp, and `createdTo` matches notifications created at or before the timestamp. These inclusive filters are useful when investigating notifications created during a specific operational time window and can be combined with `status`, `sourceEventId`, `recipient`, `type`, pagination, and sorting.

```http
GET /api/v1/admin/notifications?createdFrom=2026-06-21T00:00:00Z&createdTo=2026-06-21T23:59:59Z
```

```http
GET /api/v1/admin/notifications?status=FAILED&createdFrom=2026-06-21T00:00:00Z&createdTo=2026-06-21T23:59:59Z
```

```http
GET /api/v1/admin/notifications?sourceEventId=66666666-6666-6666-6666-666666666666&createdFrom=2026-06-21T00:00:00Z
```

```http
GET /api/v1/admin/notifications?type=ORDER_PLACED_EMAIL&createdFrom=2026-06-21T00:00:00Z&createdTo=2026-06-21T23:59:59Z
```

### Sent date filtering

Use `sentFrom` and `sentTo` to filter by `sentAt`: `sentFrom` matches notifications sent at or after the timestamp, and `sentTo` matches notifications sent at or before the timestamp. These filters are useful when listing sent notifications during a specific operational time window, are most useful with `status=SENT`, and can be combined with `recipient`, `type`, pagination, and sorting.

```http
GET /api/v1/admin/notifications?status=SENT&sentFrom=2026-06-21T00:00:00Z&sentTo=2026-06-21T23:59:59Z
```

```http
GET /api/v1/admin/notifications?status=SENT&recipient=customer@example.com&sentFrom=2026-06-21T00:00:00Z
```

```http
GET /api/v1/admin/notifications?status=SENT&type=ORDER_PLACED_EMAIL&sentFrom=2026-06-21T00:00:00Z&sentTo=2026-06-21T23:59:59Z
```

### Source event filtering

`sourceEventId` links a notification to its source outbox event. Use it to move from outbox observability to notification observability when investigating the notification produced from a specific event. The filter is an exact UUID match and can be combined with `status`, `recipient`, `type`, pagination, and sorting.

```http
GET /api/v1/admin/notifications?sourceEventId=66666666-6666-6666-6666-666666666666
```

```http
GET /api/v1/admin/notifications?sourceEventId=66666666-6666-6666-6666-666666666666&status=FAILED
```

```http
GET /api/v1/admin/notifications?sourceEventId=66666666-6666-6666-6666-666666666666&type=ORDER_PLACED_EMAIL
```

### List requeued notifications

```http
GET /api/v1/admin/notifications?requeuedOnly=true&sort=createdAt,desc&size=20
```

Use this to review notifications that have been manually requeued at least once.

### Last requeued date filtering

Use `lastRequeuedFrom` and `lastRequeuedTo` to filter by `lastRequeuedAt`: `lastRequeuedFrom` matches notifications requeued at or after the timestamp, and `lastRequeuedTo` matches notifications requeued at or before the timestamp. These inclusive filters are useful for investigating manual requeue activity in a specific operational time window, especially with `requeuedOnly=true`. Notifications with a null `lastRequeuedAt` do not match when a last requeued range filter is active. These filters can be combined with `status`, `sourceEventId`, `recipient`, `type`, pagination, and sorting.

```http
GET /api/v1/admin/notifications?requeuedOnly=true&lastRequeuedFrom=2026-06-21T00:00:00Z&lastRequeuedTo=2026-06-21T23:59:59Z
```

```http
GET /api/v1/admin/notifications?status=PENDING&lastRequeuedFrom=2026-06-21T00:00:00Z
```

```http
GET /api/v1/admin/notifications?sourceEventId=66666666-6666-6666-6666-666666666666&lastRequeuedFrom=2026-06-21T00:00:00Z
```

```http
GET /api/v1/admin/notifications?type=ORDER_PLACED_EMAIL&lastRequeuedFrom=2026-06-21T00:00:00Z&lastRequeuedTo=2026-06-21T23:59:59Z
```

### Last requeued by filtering

Use `lastRequeuedBy` to filter notifications by the administrator email that last manually requeued them. The filter uses case-insensitive contains matching, ignores blank values, and does not match notifications where `lastRequeuedBy` is null. It is useful for investigating requeue activity by administrator, works well with `requeuedOnly=true`, and can be combined with `lastRequeuedFrom`, `lastRequeuedTo`, `status`, `sourceEventId`, `recipient`, `type`, pagination, and sorting.

```http
GET /api/v1/admin/notifications?lastRequeuedBy=admin@example.com
```

```http
GET /api/v1/admin/notifications?requeuedOnly=true&lastRequeuedBy=admin@example.com
```

```http
GET /api/v1/admin/notifications?lastRequeuedBy=admin@example.com&lastRequeuedFrom=2026-06-21T00:00:00Z&lastRequeuedTo=2026-06-21T23:59:59Z
```

```http
GET /api/v1/admin/notifications?status=PENDING&lastRequeuedBy=admin@example.com
```

```http
GET /api/v1/admin/notifications?sourceEventId=66666666-6666-6666-6666-666666666666&lastRequeuedBy=admin@example.com
```

### Search notifications by recipient

```http
GET /api/v1/admin/notifications?recipient=customer@example.com&sort=createdAt,desc&size=20
```

Use `recipient` to inspect delivery history for a specific email address or recipient value.

### Inspect one notification

```http
GET /api/v1/admin/notifications/11111111-1111-1111-1111-111111111111
```

Use the detail endpoint to inspect a notification's current state, type, recipient, delivery timing, and requeue fields.

### Inspect action logs for one notification

```http
GET /api/v1/admin/notifications/11111111-1111-1111-1111-111111111111/actions?sort=createdAt,desc&size=20
```

Use notification-specific action logs to audit who requeued a notification and when the action was recorded. `REQUEUE` entries include `actorEmail`, `createdAt`, `actionType`, `notificationId`, and `details`. For manual requeue actions, `details` currently contains `Manual requeue requested for failed notification.`

### Search global notification action logs by actor and time window

```http
GET /api/v1/admin/notification-actions?actorEmail=admin@example.com&createdFrom=2026-06-21T00:00:00Z&createdTo=2026-06-21T23:59:59Z&sort=createdAt,desc&size=50
```

Use global action log search to inspect admin activity by `actorEmail`, `createdFrom`, `createdTo`, `notificationId`, or `actionType`.

## Operational notes

- These endpoints are admin-only.
- Requeue is available only according to the current notification service rules.
- Requeue records admin action log entries for auditability. `REQUEUE` action log entries include a deterministic `details` value: `Manual requeue requested for failed notification.` This makes the audit entry self-descriptive for admins and admin UI consumers while preserving the existing requeue behavior.
- The `details` field is informational and does not change delivery, retry, requeue, scheduling, or persistence behavior.
- Delete endpoints, manual status mutation, and retry-now operations are intentionally not part of this API.
- This remains part of the modular monolith. It does not introduce Kafka, RabbitMQ, external brokers, or microservices.
Notifications in `PROCESSING` are actively claimed until `claim_expires_at`.
An expired claim is abandoned work and will be reclaimed by a poller (or changed to
`FAILED` when its last allowed attempt has already been consumed). A sustained or
growing population of expired claims indicates worker termination, sender latency
greater than the configured claim duration, or finalization failures. `PENDING`
continues to distinguish due work from a scheduled retry through `next_attempt_at`.
