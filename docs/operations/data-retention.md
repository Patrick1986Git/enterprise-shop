# Operational data retention and archival boundary

## Decision

**No production age-based purge is currently authorized by repository-owned policy.**

The repository does not encode safe numeric retention periods for operational records. Legal,
business, privacy, provider, and deployment retention periods must not be invented. A numeric
cutoff requires explicit external policy and correctness evidence before cleanup is designed or
enabled.

This is a retention boundary, not a cleanup procedure. The application has no authorized purge
worker, maintenance endpoint, or archive facility.

## Retention classes

### Class 1 — correctness-critical

These records must not be deleted merely because they are old:

- `stripe_webhook_events`, whose provider event IDs enforce exact webhook replay protection;
- `stripe_payment_conflicts`, whose immutable observations retain authenticated provider/local contradiction evidence;
- actionable or recoverable `outbox_events`;
- `PENDING` and `PROCESSING` notifications;
- `FAILED` notifications while ADMIN requeue remains supported;
- `reservation_expiration_work`, including `COMPLETED` rows while the unique order row remains
  the duplicate-work barrier;
- checkout idempotency keys; and
- order, payment, and provider identifiers needed for idempotency or reconciliation.

Class 1 data remains until a stronger invariant or authoritative external guarantee proves a safe
boundary. Age and terminal status alone are insufficient.

### Class 2 — bounded correctness window

**No current record family has a repository-proven numeric Class 2 window.** Possible future
candidates require authoritative evidence such as a provider replay guarantee, an explicit client
idempotency retry contract, or a proven reconciliation horizon. This document assigns no numeric
value to those windows.

### Class 3 — operational or audit history

This class includes:

- processed outbox history after all correctness dependencies end;
- sent notification history after source-event deduplication dependencies end;
- failed or dead-letter forensic history after recovery is formally closed;
- `notification_admin_action_logs`, `outbox_event_admin_action_logs`, and
  `reservation_expiration_admin_action_logs`; and
- `stripe_payment_conflict_dispositions`, whose investigation-accountability lifetime is coupled to the retained
  Class 1 conflict evidence.

Correctness may eventually stop depending on a Class 3 record, but its retention duration remains
business, security, legal, and deployment owned.

### Class 4 — disposable implementation residue

**None currently proven.**

## Stripe webhook invariant

`stripe_webhook_events.stripe_event_id` is the exact durable provider-event replay barrier. The
webhook registrar inserts the event ID before handling it and treats a uniqueness conflict as a
duplicate. Deleting that row can make the same repeated, correctly signed Stripe event appear new
again.

Order and payment terminal transitions converge in several repeated-delivery cases. That reduces
the risk of repeated terminal mutation, but it is not a substitute for exact provider-event
idempotency and does not establish a safe replay horizon.

A late succeeded intent is an idempotent no-op only after amount, PLN currency, provider identity,
and the local terminal pair have been reconciled. `PAID` (or its later `SHIPPED` state) with a
`COMPLETED` payment is consistent; a missing local provider attachment is recovered in that case.
An identity or monetary mismatch, or succeeded provider evidence for another local terminal pair
such as `CANCELLED`/`FAILED`, fails closed without reversing the order or restoring inventory. The
webhook transaction then rolls back its event-ID insertion, so Stripe retry remains possible and
the repeated operational failure remains visible rather than being durably consumed.

Therefore:

- do not age-purge webhook event IDs;
- do not invent a Stripe replay duration; and
- require an authoritative provider replay and reconciliation contract before considering archive,
  tombstone, or purge behavior.

`stripe_payment_conflicts` is independent of the replay barrier. Its rows remain after rejected webhook transactions
roll back and retain the minimum sanitized provider/local evidence after logs or provider delivery history expire.
Runtime credentials cannot update or delete these observations. No conflict-evidence purge is authorized.

## Outbox invariant

### `PENDING` and retryable

Never age-purge an actionable event. An old event may represent an extended outage or scheduled
retry, not abandoned work. This rule also applies after manual requeue returns an event to
`PENDING`.

### `FAILED` and `DEAD_LETTER`

These rows remain investigation, payload-inspection, recovery, and manual-requeue targets. Their
error, dead-letter, attempt, and action-history context can be incident evidence. A failed or
dead-letter state does not make an event disposable.

### `PROCESSED`

A processed event is only a potential future retention candidate after all of these conditions are
met:

- notification source-event deduplication remains effective without the full row;
- ADMIN query, action-history, and forensic requirements are resolved; and
- the changed backup and disaster-recovery recovery set is explicitly accepted.

No processed-event deletion is currently authorized.

## Notification invariant

- `PENDING` and `PROCESSING` rows are correctness-critical delivery or claim-recovery work.
- `FAILED` rows remain recoverable through ADMIN requeue and retain delivery-failure evidence.
- `SENT` rows currently retain source-event deduplication evidence through the unique non-null
  `source_event_id` value.
- `recipient`, `subject`, and `body` contain potentially personal or sensitive information; errors
  and requeue actor fields may also contain identifiers.

Any future notification policy must make three independent decisions rather than using one
arbitrary period:

1. the correctness minimum for delivery, claim recovery, requeue, and source-event deduplication;
2. the operational-debugging and incident-investigation period; and
3. the business, legal, and privacy retention period for content and metadata.

## ADMIN action-history invariant

The ADMIN history tables are append-only for runtime credentials:

- `notification_admin_action_logs`;
- `outbox_event_admin_action_logs`;
- `reservation_expiration_admin_action_logs`; and
- `stripe_payment_conflict_dispositions`.

Stripe conflict dispositions have a non-cascading foreign key to the Class 1 conflict observation. They record review
or escalation only and never prove financial correction. The foreign key preserves investigation context; it does not
authorize deletion of either record family.

Do not add runtime cleanup for these tables or weaken V45. If future policy requires deletion, it
must use a distinct privileged administrative or migration identity, be independently audited, and
resolve legal holds, export requirements, and other preconditions first. Execution must use bounded
batches. This repository currently provides no such maintenance mechanism.

## Reservation-expiration work invariant

`reservation_expiration_work.order_id` is unique. The persisted row therefore participates in
preventing duplicate adoption or enqueue for an order. A `COMPLETED` row is not automatically
disposable.

Future deletion requires proof that every creation and legacy-adoption path remains duplicate-safe
without the row, or a replacement tombstone that preserves the invariant.

## Relationship warning

Several operational relationships are application-level references rather than database foreign
keys, including:

- `notifications.source_event_id` to `outbox_events.id`;
- notification ADMIN history to its notification;
- outbox ADMIN history to its outbox event; and
- reservation ADMIN history to its order and work row.

**Absence of a foreign key is not evidence that deletion is safe.** Deleting a referenced record
can remove drill-down context, break deduplication assumptions, or leave logically orphaned audit
evidence. Cascade deletion of audit history is not an approved retention strategy.

## Growth and disaster recovery

Indefinite retention increases table and index size, logical-backup size, restore duration,
post-restore reconciliation work, and pressure on the feasible RTO. Premature deletion can instead
remove replay or idempotency barriers, provider reconciliation evidence, recovery targets, and
operator history. Storage optimization must not override correctness.

This boundary extends the [PostgreSQL disaster-recovery contract](./disaster-recovery.md). A purge
changes the authoritative recovery set because deleted data will not appear in later backups. Any
future retention mechanism must be included in restore rehearsals, and must not remove the only
evidence available to reconcile Stripe or another provider with restored local state.

## Required external inputs

Before implementing any purge or archive operation, record and approve at least:

- authoritative Stripe replay and provider/local reconciliation guarantees;
- business, legal, and privacy retention decisions;
- the incident-investigation and manual-recovery horizon;
- notification content-versus-metadata retention policy;
- the checkout-idempotency replay contract;
- archive destination, access, integrity, verification, and destruction policy when archival is
  required;
- backup, PITR, restore-rehearsal, and RPO/RTO interaction;
- the privileged maintenance identity and independent audit destination; and
- measured production cardinality and table/index growth.

This repository does not interpret GDPR, CCPA, tax, accounting, or other legal requirements. Those
decisions are external inputs to a later engineering change.

## Constraints on a future implementation

Any later authorized retention mechanism must provide:

- bounded batches and indexed cutoff predicates;
- deterministic status and precondition checks that exclude actionable work;
- restart-safe, idempotent behavior and multi-replica safety;
- no large or unbounded delete transaction;
- low-cardinality outcomes and row-count observability;
- no logging of notification bodies, outbox payloads, secrets, or personal-data dumps;
- verified archival before deletion when archival is required;
- durable tombstones where identity must survive but payload need not; and
- privileged execution for ADMIN history retention without expanding runtime privileges.

Prefer deployment-owned privileged maintenance initially over another runtime `@Scheduled` worker.
A runtime scheduler requires separate evidence that its ownership, authorization, locking,
multi-replica, and failure-recovery model is appropriate.
