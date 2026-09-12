# Order and payment state-machine contract

This document records the state machine implemented by the order module. Enum membership and database acceptance are
not evidence that an application transition exists. In particular, fulfillment and refunds are outside the currently
implemented lifecycle.

## Supported lifecycle

Checkout atomically reserves inventory and creates an `Order` in `NEW`, a related `Payment` in `PENDING`, reservation
expiration work, and an `OrderPlaced` outbox event. The cart is not cleared at checkout. PaymentIntent initialization
may attach provider details and may move a non-completed payment from `FAILED` back to `PENDING`; it does
not change the order or inventory.

The supported, persistently stable pairs are:

| Order | Payment | Meaning |
| --- | --- | --- |
| `NEW` | `PENDING` | Inventory is reserved and provider payment may be initialized or awaiting an outcome. |
| `NEW` | `FAILED` | A provider failure was observed. The reservation remains owned by the order and payment may be retried. |
| `PAID` | `COMPLETED` | Provider success was validated and committed; purchased quantities were reconciled from the cart. |
| `CANCELLED` | `FAILED` | Provider cancellation was validated and reserved inventory was released in the same transaction. |

There is no customer or ADMIN status mutation API. `NEW -> PAID` is provider-driven by a verified
`payment_intent.succeeded` event or by reservation-expiration reconciliation of a retrieved succeeded PaymentIntent.
`NEW -> CANCELLED` is provider/scheduler-driven after a canceled PaymentIntent is validated. A
`payment_intent.payment_failed` event changes `PENDING` (or a previous `FAILED`) to `FAILED` without cancelling the
order or releasing inventory. Reservation expiration retrieves provider state and either converges through the same
terminal transition service or retains retryable work; it does not infer a terminal state from elapsed time.

Succeeded and canceled terminal convergence runs in a database transaction. It locks the `Order` first and its
`Payment` second with pessimistic write locks. The success transaction validates provider identity, amount, and
currency, writes `PAID + COMPLETED`, and reconciles the cart. The cancellation transaction validates the same provider
evidence, releases reserved inventory, and writes `CANCELLED + FAILED`. A failure transaction uses the same lock order
and changes only a non-completed payment to `FAILED`. Database-backed inventory and state writes roll back together if
the transaction fails. Calls to Stripe occur outside these terminal database transactions.

`PAID + PENDING` exists briefly only as an in-memory ordering detail inside successful convergence, before the payment
is marked completed and the transaction commits. `CANCELLED + PENDING` is analogous inside cancellation convergence.
Neither is a supported committed pair.

## Reserved and legacy-compatible values

`OrderStatus.SHIPPED` is reserved/legacy-compatible. No production domain method, controller, ADMIN operation,
notification, outbox handler, or scheduler transitions an order to it. The database continues to accept it because a
historical migration made it part of the persisted vocabulary and this repository cannot prove that deployed
databases contain no such rows. A persisted `SHIPPED + COMPLETED` pair is treated as a legacy terminal success for
idempotent succeeded-webhook replay. Tests construct that legacy state explicitly because no supported production
transition exists. This compatibility does not promise fulfillment, shipment tracking, or a future shipping API.

`PaymentStatus.REFUNDED` is also reserved/legacy-compatible. No production method enters or exits it, no handled Stripe
event represents a refund, and the application has no refund command or corresponding order state. Every pair
involving `REFUNDED` is therefore unsupported/undefined application state, even though PostgreSQL can retain such a
legacy row. The application makes no inventory-restoration promise for it. Refund decisions and compensation remain
an external operator/business responsibility until an explicit reviewed contract is implemented.

Removing either value would require deployment data evidence and a new forward Flyway migration. Historical
migrations must not be rewritten merely to narrow the current application contract.

## Inconsistent pairs

The following committed combinations cannot be produced from a consistent supported pair by the production state
machine:

- `PAID + PENDING`, `PAID + FAILED`, `SHIPPED + PENDING`, and `SHIPPED + FAILED`;
- `CANCELLED + PENDING` and `CANCELLED + COMPLETED`;
- `NEW + COMPLETED`;
- every combination containing `REFUNDED`.

Except for the explicitly noted in-transaction pairs, these combinations imply legacy data, direct database mutation,
an interrupted historical implementation, or model drift. `NEW + COMPLETED` can be recovered by validated succeeded
convergence to `PAID + COMPLETED`. A validated succeeded replay for `PAID + COMPLETED` or legacy
`SHIPPED + COMPLETED` is an idempotent no-op. Other inconsistent terminal combinations have no automatic repair
contract and require investigation rather than blind mutation.

## Contradictory provider success after local cancellation

A succeeded PaymentIntent presented for local `CANCELLED + FAILED` fails closed. The webhook transaction rolls back,
including event-ID registration, so Stripe may retry it; inventory is not re-reserved, the cart is not reconciled, and
no notification/outbox event is emitted. Repeated delivery is deliberately not automatic compensation. The conflict
log includes the order ID, payment ID, provider PaymentIntent ID, and both local statuses. For the supported
cancellation path, `CANCELLED + FAILED` also proves that reserved inventory was released in the same committed
transaction; that inference is not safe for directly mutated or unverified legacy data.

Operators must use those identifiers with Stripe's event/delivery record and protected order reads to establish the
provider and local histories before deciding an external refund or other reviewed compensation. A direct webhook
conflict has no repository-owned requeue command: retryability means that event registration was rolled back, not that
the contradiction will heal. When the same conflict is found by reservation expiration, the work record additionally
retains bounded attempts and the last error for protected ADMIN inspection and recovery. Replaying either path without
changing the underlying contradictory facts will fail again.

## Persistence and ownership boundaries

PostgreSQL guarantees foreign keys, one payment per order, non-null columns, numeric constraints, and membership of
the two status columns in their full persisted vocabularies. It does not enforce cross-table order/payment pairs.
JPA enum mapping prevents unknown values from loading, while entity methods constrain the normal order transitions and
protect completed payments from being downgraded by pending/failed transitions. The terminal transition service owns
cross-aggregate validation, lock order, transactional state convergence, inventory release, and success-time cart
reconciliation. Stripe signature verification and PaymentIntent identity, amount, and currency validation establish
provider evidence before mutation.

Cross-table pair validity, legacy interpretation, refund/fulfillment absence, and contradictory-provider handling
necessarily remain application and operational invariants. A cross-table SQL check constraint would not express these
safely, and no schema change is justified by this contract audit.

The only status-related event currently emitted is `OrderPlaced`, recorded during checkout while the order is `NEW`.
No transition-specific outbox event or notification is emitted for `PAID`, `CANCELLED`, `SHIPPED`, payment failure, or
refund.
