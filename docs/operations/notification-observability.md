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

## Common operational queries

`GET /api/v1/admin/notifications` supports `status`, `type`, `recipient`, `lastErrorContains`, `requeuedOnly`, `attemptsMin`, `attemptsMax`, `lastAttemptFrom`, `lastAttemptTo`, `sentFrom`, `sentTo`, `deliveryState`, pagination, and sorting. `GET /api/v1/admin/notification-actions` supports `notificationId`, `actionType`, `actorEmail`, `createdFrom`, `createdTo`, pagination, and sorting.

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

### List requeued notifications

```http
GET /api/v1/admin/notifications?requeuedOnly=true&sort=createdAt,desc&size=20
```

Use this to review notifications that have been manually requeued at least once.

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

Use notification-specific action logs to audit who requeued a notification and when the action was recorded.

### Search global notification action logs by actor and time window

```http
GET /api/v1/admin/notification-actions?actorEmail=admin@example.com&createdFrom=2026-06-21T00:00:00Z&createdTo=2026-06-21T23:59:59Z&sort=createdAt,desc&size=50
```

Use global action log search to inspect admin activity by `actorEmail`, `createdFrom`, `createdTo`, `notificationId`, or `actionType`.

## Operational notes

- These endpoints are admin-only.
- Requeue is available only according to the current notification service rules.
- Requeue records admin action log entries for auditability.
- Delete endpoints, manual status mutation, and retry-now operations are intentionally not part of this API.
- This remains part of the modular monolith. It does not introduce Kafka, RabbitMQ, external brokers, or microservices.
