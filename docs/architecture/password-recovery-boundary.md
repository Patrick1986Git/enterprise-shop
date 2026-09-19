# Forgotten-password recovery boundary

## Audit basis and decision

This decision was audited against protected `master` at
`6c173326799a224823d9494adfd75903863bc8a1`. PR #388 is present through commit
`e6ca3c63c9eb3448522c4eb71e0906e4616738cf`; the protected-head build, Docker validation,
container-security, Pages, and CodeQL check runs were successful at the audit boundary. The public repository reported no
open pull requests or issues.

**Forgotten-password recovery remains intentionally unsupported.** The repository does not prove a complete secret-delivery
and operational contract, so it would be unsafe to add token issuance, a public request endpoint, or reset consumption. This
is a fail-closed architecture decision rather than a partial implementation. Registration, login, and authenticated password
change remain the only credential lifecycle operations.

## Proven current boundary

`AuthController`, `AuthService`, and `AuthServiceImpl` expose only registration and login. Authenticated password change is
owned by the User service. It acquires the transaction-scoped User lifecycle advisory lock, reloads the active User, applies
the canonical password validation and repository-owned BCrypt encoder, and increments `credential_version`. The JWT filter
reloads the active User and roles for every bearer request and accepts the token only when its credential-version claim equals
the persisted version. The JWT subject remains the normalized email.

Retirement uses the same lifecycle lock and soft-deletes the User without releasing the globally unique normalized email.
Nothing in a future recovery flow may reactivate that row, release that identifier, migrate the JWT subject, or bypass the
active-User reload.

The generic notification subsystem is a durable, at-least-once delivery workflow:

- `notifications` persists the recipient, subject, and complete body in PostgreSQL;
- a delivery worker claims a row and passes that persisted content to `NotificationSender`;
- `SmtpNotificationSender` constructs SMTP mail from the claimed persisted content;
- failed delivery metadata and generic notification content have no authorized age-based purge;
- protected ADMIN notification APIs expose stored notification details, including the body; and
- database backups include the notification table, while SMTP configuration proves only local binding and cannot prove
  provider routing, recipient acceptance, TLS policy, delivery latency, or retry semantics.

Outbox payloads are also durable, inspectable operational records with no authorized purge. Neither the notification body nor
an outbox payload is therefore a permissible location for a reusable password-reset bearer credential. The observability
contract already forbids credential or payload logging and high-cardinality secret-bearing metric labels, but that prohibition
does not transform the durable generic delivery workflow into a secret courier.

## Threat model and mandatory future invariants

A raw reset credential grants control of an account without the current password. Database readers, backup readers,
notification administrators, outbox administrators, log readers, metrics consumers, and operators must not gain that ability
merely through their existing access. A future implementation must also resist public account enumeration, replay, concurrent
consumption, reset-versus-retirement races, reset-versus-password-change races, and rollback-induced partial state.

At minimum, a future dedicated persistence model must associate one cryptographic verifier with exactly one User; store no
reusable raw token; record explicit expiry and consumed/revoked state; enforce single use; define whether a new request
supersedes every earlier token; and have indexes and database constraints supporting atomic lookup and consumption. Token
generation must use a cryptographically secure random source with enough entropy for an online bearer secret. Verification
should use a dedicated one-way digest/verifier contract, not the access-JWT signing key, email, or a JWT access token.

Consumption must acquire the User lifecycle lock, reload and recheck the active account after locking, validate the replacement
with the same canonical character and 72-byte UTF-8 rules, BCrypt-encode it, increment `credential_version`, and consume or
revoke the reset record in one transaction. Retirement must always win or make reset fail closed. Authenticated password change
must revoke outstanding reset credentials under the selected policy. A successful reset must invalidate every pre-reset access
JWT through the incremented credential version and must never restore a retired User.

## Delivery alternatives evaluated

### A. Opaque token with hash at rest

This is the required persistence shape, but it does not by itself solve delivery. Hashing the token before storage prevents
database recovery of the raw value; the asynchronous SMTP worker would then have nothing it can send. Persisting the raw token
in `notifications.body`, an outbox payload, an error, an audit record, or another retry table would merely move the plaintext
credential into PostgreSQL and its backups. Passing it only in process memory loses it on a crash before asynchronous delivery
and supplies no repository-proven retry contract. **Rejected as incomplete without a separate secure delivery design.**

### B. Encrypted delivery-only payload

Authenticated encryption could make a durable delivery payload opaque to ordinary database and backup readers, but the
repository has no recovery-delivery encryption key, key identifier, envelope format, rotation overlap, re-encryption or
destruction procedure, startup validation, access separation, incident response, or backup/restore ownership. The application
and any notification administrator response would also need strict plaintext exclusion, and retry retention would extend the
credential ciphertext lifetime. Reusing `JWT_SECRET` would couple unrelated compromise and rotation domains and is not
authorized. **Deferred until operators own an explicit key-management and retention contract.**

### C. Dedicated after-commit mail delivery

A transaction synchronization or application event could hand an in-memory raw token to a narrow sender only after the reset
verifier commits, avoiding provider I/O inside the database transaction. It cannot, however, atomically guarantee delivery. A
crash between commit and send strands a valid token; a provider timeout creates ambiguous delivery; retry needs durable secret
material; and synchronous request completion or outcome-dependent retries can introduce enumeration and timing differences.
The current SMTP contract is optional, may select a no-op sender, and supplies no recovery-mail availability objective.
**Rejected because the repository proves neither acceptable loss semantics nor a secure retry path.**

### D. Provider-side template or secret handle

A provider that durably owns a template invocation or one-time secret could avoid storing the raw credential in generic local
content. No such provider API, template identifier, secret-handle facility, webhook contract, credential scope, idempotency
contract, data-retention agreement, or production dependency exists in this repository. Generic SMTP accepts a complete body;
it is not a provider-side template service. **Deferred pending a concrete provider and operational contract.**

## Expiry, retention, and enumeration decisions still required

No repository-owned security, product, or operational requirement supplies a numeric reset-token lifetime. A future change must
either document an approved lifetime or introduce a positive, startup-validated, operator-owned production property with no
hidden Java default. It must also define cleanup timing for expired, consumed, and revoked records and reconcile that timing
with backup/PITR, incident investigation, and the current policy that authorizes no age-based production purge.

The future reset-request response must be identical for active, disabled, retired, and unknown normalized emails and must never
return the token in production. Status, schema, headers, and body must be the same. Repository-controlled timing must be made
substantially equivalent; in particular, only active-account requests must not synchronously wait for SMTP while other cases
return immediately. Provider traffic can itself disclose account existence to provider operators, so the selected delivery
contract must state whether indistinguishable decoy work is required and who accepts its cost and abuse implications. Rate
limits and abuse controls must not use secret- or email-valued metric labels or logs.

## Verification required before enabling recovery

A future implementation requires API tests for active, unknown, retired, and disabled requests; equivalent external responses;
token exclusion from HTTP, generic notifications, outbox, errors, logs, metrics, audit records, and ADMIN views; valid and every
invalid consumption state; password confirmation; the BCrypt UTF-8 boundary; login transition; and stale-JWT rejection.
PostgreSQL/Testcontainers tests must prove at-most-one success for two consumers, reset versus retirement, reset versus
authenticated password change, concurrent requests under the chosen supersession rule, expiry, replay, and transaction rollback.

Until those contracts and tests exist, there is intentionally no reset schema, migration, token generator, endpoint, OpenAPI
operation, delivery extension, cleanup worker, or recovery metric. Unsupported scenarios include operator-issued resets,
administrator viewing or copying a reset link, reset by current email as a credential, access-JWT reuse, reactivation of retired
accounts, and recovery when the deployment has not established the approved mail-delivery contract.

## Required handoff inputs

The security/product and deployment owners must approve:

1. the reset-token lifetime and expired/consumed/revoked retention policy;
2. one secure raw-token delivery design, including crash, ambiguous-send, retry, and provider-outage behavior;
3. for encrypted delivery, key custody, access separation, rotation, restore, compromise, and destruction procedures;
4. for provider templates, provider identity, credential scope, idempotency, template/version ownership, data handling, and SLA;
5. enumeration timing, decoy-delivery, abuse-control, and rate-limit policy;
6. supersession and authenticated-password-change revocation semantics; and
7. production sender, transport-security, deliverability, monitoring, and incident-response ownership.
