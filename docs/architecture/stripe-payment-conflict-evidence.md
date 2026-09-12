# Stripe payment conflict evidence

## Decision

Authoritative, fully reconciled Stripe success that contradicts committed terminal Order/Payment state is
repository-owned financial evidence. Logs and Stripe delivery history alone are not an adequate boundary: provider
retry eventually ends, while local log retention and provider-history retention are deployment/provider concerns and
the repository defines no guarantee that either will outlive an investigation. The application therefore stores one
immutable observation per authenticated Stripe event (**Outcome B**).

This is evidence, not a recovery workflow. ADMIN users can list and inspect it and append a separate investigation
disposition. `ACKNOWLEDGED` means only that the authenticated administrator reviewed the evidence. `ESCALATED` means
only that the administrator handed it to the appropriate operational or financial investigation process. Neither
action proves correction, reconciliation, refund, compensation, or any Stripe operation. Administrators cannot edit
the observation, replay the webhook, refund, re-reserve inventory, or force a local state transition through this API.

Each genuine administrator action is retained, including repeated or concurrent actions; there is no heuristic
deduplication. The actor email comes exclusively from the authenticated server-side current-user context and is not a
request field. Disposition history is independently append-only and ordered by `createdAt DESC, id ASC`. It is queried
separately with bounded pagination rather than embedded in conflict-list results.

The default operator list order is `observedAt DESC, id ASC`. The explicit ascending UUID tie-break is also appended
to custom sorts that omit `id`, providing stable pagination and matching the `(observed_at DESC, id ASC)` PostgreSQL
operator-query index.

## Trust and failure taxonomy

Signature verification runs before an event is registered or any local financial row is locked. A malformed payload,
invalid/missing signature, or missing authenticated event ID/type is rejected without conflict evidence. Unsupported
types and authenticated events whose Stripe object cannot be deserialized are committed as consumed/ignored events;
they are not financial contradictions.

For supported PaymentIntent events, the replay-barrier insert precedes metadata and business validation. Missing or
invalid order metadata, missing Order/Payment, missing or mismatched amount, non-PLN currency, and provider-intent
identity mismatch reject the event. They are malformed, referential, or provider-integrity failures rather than proof
of a valid provider/local state contradiction, so they create no conflict row. Their event registration rolls back.

Only `payment_intent.succeeded` reaches conflict recording, and only after all of the following are true:

- the Stripe signature, event ID, and event type were accepted;
- order metadata is a UUID and the Order and Payment have been locked and found;
- amount and PLN currency match the Order;
- the provider PaymentIntent ID matches an existing attachment, or can be attached transactionally; and
- the local pair is neither `NEW` nor the consistent `PAID + COMPLETED` / legacy `SHIPPED + COMPLETED` pair.

This includes `PAID` or `SHIPPED` with `PENDING`/`FAILED`, and `CANCELLED` with
`PENDING`/`FAILED`/`COMPLETED`. The state-machine rejection is a 500
`STRIPE_WEBHOOK_PROCESSING_ERROR`; Stripe should retry. The webhook transaction rolls back its replay-barrier insert,
provider attachment, and every Order/Payment mutation. Inventory and cart reconciliation do not run.

## Transaction and identity model

`PaymentServiceImpl.handleWebhook` remains the transactional replay and reconciliation boundary. It exits by throwing a
typed conflict containing only bounded identifiers and enum state. `StripeWebhookProcessor`, which is deliberately
non-transactional, catches that exception only after the Spring proxy has rolled back the transaction. It then calls
the recorder in a new ordinary transaction and rethrows the original conflict.

This sequencing does not use `REQUIRES_NEW`: no suspended transaction retains Order/Payment row locks while the
evidence insert runs. Conflict rows intentionally have application-level UUID references rather than foreign keys, so
the insert performs no PostgreSQL FK check against formerly locked financial rows. The unique Stripe event ID and
`ON CONFLICT DO NOTHING` make same-event retries and concurrent identical deliveries idempotent. Different Stripe event
IDs are independent provider observations even when they concern one PaymentIntent; preserving each signed provider
observation avoids guessing that Stripe events have interchangeable semantics. Later successful reconciliation never
deletes historical evidence.

There is an unavoidable process-failure interval between rollback and the independent evidence commit. The replay row
is absent throughout, so a crash or evidence-write failure returns/fails as a webhook error and leaves Stripe retry as
the recovery mechanism; it never consumes the event or mutates financial/cart/inventory state. A successful evidence
commit survives application restart.

## Stored and excluded data

The immutable row stores the local conflict UUID, Order and Payment UUIDs, Stripe event and PaymentIntent IDs, the
finite event type/reason, local status enums, and observation timestamp. These fields support event idempotency and
ADMIN correlation. It never stores the raw payload, Stripe signature, secrets, client secret, card data, authorization
headers, JWTs, remote messages, or arbitrary exception text. Identifiers appear in structured conflict logs and the
protected DTO, never in metric tags.

The table is Class 1 correctness-critical evidence under the repository retention boundary. No age-based deletion is
authorized. PostgreSQL rejects runtime-role updates/deletes while permitting the migration owner to administer schema;
the evidence is therefore append-only under application credentials.

Disposition rows reference their retained conflict with a non-cascading foreign key. They are Class 3 append-only
audit history whose lifetime is coupled to the Class 1 conflict evidence: no age-based deletion or runtime cleanup is
authorized, and removal of the parent while its investigation history exists is rejected by PostgreSQL.
