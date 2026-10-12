# Password recovery security foundation

## Approved scope

On 2026-10-12 the owner approved a non-public local/test foundation: independent 256-bit secrets, purpose-bound SHA-256
verifiers, exactly 15-minute expiration, one open challenge per User, newest committed issuance superseding previous
authority, atomic one-time consumption and no automatic deletion.

**End-user password recovery remains unsupported.** There is no recovery controller, OpenAPI operation, delivery worker,
activation property or real courier supplied by V52. The package-private PasswordRecoveryService has no Spring
stereotype or configuration factory; only same-package tests construct it. ArchUnit and application-context tests protect
this boundary. MemoryRecoveryCapture exists only in test sources and is absent from the packaged application.

Missing, disabled, retired and ROLE_ADMIN Users are ineligible at issuance and consumption. No operation changes email,
roles, enabled or deleted state. This foundation is separate from V51's local offline administrator promotion.

V53 separately implements the owner's approved local sole-ADMIN credential recovery using pre-enrolled independent
emergency proof and a distinct reset-only identity. It does not activate or broaden V52. The separate
[administrator credential contract](./local-administrator-credential-recovery.md) and
[local runbook](../operations/local-administrator-credential-recovery.md) govern that manually invoked capability.

## Cryptographic and password contracts

The opaque format is `v1.<canonical lowercase challenge UUID>.<canonical unpadded Base64url of 32 random bytes>`.
The selector is not authority. Java SecureRandom generates the independent secret; email, time, UUIDs, JWTs and signing
keys are not secret sources.

The SHA-256 input has a four-byte big-endian purpose length, ASCII purpose `enterprise-shop/password-recovery`, two-byte
format version, challenge and target UUIDs as their two big-endian longs, eight-byte issued credential version and decoded
32-byte secret. This unambiguous encoding binds purpose, format, selector, target and generation. MessageDigest.isEqual
performs verification. Malformed, unsupported and noncanonical values fail without input-bearing diagnostics.

Replacement validation reuses NotBlank, the existing 8–72-character rule, Utf8Length's 72-byte ceiling and confirmation
equality. The existing BCrypt bean encodes without trimming or normalization. User.changePassword advances credential_version
atomically; existing per-request JWT validation invalidates previous tokens. No auto-login or session store is added.

## Persistence and transactions

V52 stores only selector, target UUID, format, verifier, issued version and lifecycle timestamps. CHECK constraints enforce
verifier length, finite timestamps, exactly 15 minutes and mutually exclusive terminal states. The User FK is RESTRICT;
retirement leaves the account and email reserved. Triggers enforce immutable issuance, irreversible terminal state,
eligible/current-version insertion and consumption, and runtime DELETE/TRUNCATE denial even after broad grants.
Migration owners and superusers remain trusted authorities.

The partial unique index selects rows with neither terminal timestamp. Expired rows do not automatically leave it.
Issuance revokes the prior open row before replacement; rollback preserves the previous challenge. Expiration governs
validity, not retention, and no cleanup is implemented.

Each command owns a new READ COMMITTED transaction. The exact order is:

1. Existing exclusive administrator advisory domain (1094995278, 1).
2. Existing bigint User lifecycle domain, hashtextextended(uuid::text, 0).
3. Fresh authoritative User lookup and User row lock.
4. Challenge row lock and mutations.
5. Atomic commit.

Scalar selector routing grants no authority. V52 reuses V51's statement serialization for challenge writes and introduces
no second synchronization domain. Commerce-only operations retain their existing protocol. Never call recovery from a
transaction that already took a User lock before the administrator domain.

Consumption rechecks eligibility, version, open state and digest. After BCrypt work the guarded UPDATE uses PostgreSQL
clock_timestamp, accepting only expiry strictly greater than the observation; equality rejects. The trigger checks again
at the transition. Consumption, password and version changes roll back together on constraint/commit failure, overflow,
timeout or deadlock. SKIP LOCKED is not used.

Version binding invalidates older challenges after authenticated password change, disablement and promotion; enablement
cannot restore a prior version. Retirement and ADMIN exclusion are independently checked. Concurrent commands serialize
and recheck after a committed winner.

## Test delivery and restore boundary

The synthetic memory capture receives a token only after successful commit and captures nothing on failure. It has no
logger, persistence, notification/outbox dependency or production registration. No real email is sent. Tests avoid raw-token
assertion diagnostics and parameterized display names.

An older backup can restore open authority consumed after its recovery point. Expiry alone is insufficient and JWT key
rotation does not invalidate opaque challenges. There is currently no activation path. Before any separately reviewed
future activation, keep traffic/couriers stopped and explicitly invalidate every open restored challenge using trusted
migration/maintenance credentials:

```sql
UPDATE password_recovery_challenges
SET revoked_at = clock_timestamp()
WHERE consumed_at IS NULL AND revoked_at IS NULL;
```

Verify no open rows remain. Retain history and do not replay delivery. The dump/restore integration test and exact-version
restore rehearsal prove that maintenance boundary. Rotate JWT signing material separately when older credential versions
are restored. PostgreSQL owns coordination time; trustworthy host time/failover and clock incidents remain operator-owned.

## Remaining public and production decisions

The local/test approval does not authorize public request/consume APIs, ADMIN recovery, admission/abuse budgets,
enumeration/timing controls, browser origin/link handling or real delivery.

Generic notifications persist full bodies and expose them to ADMIN. Outbox payloads and provider exception messages are
also durable and inspectable. Neither may contain reusable recovery credentials.

Opaque verifiers alone do not solve delivery. Memory-only after-commit mail loses work on crashes and has ambiguous
timeouts; loss/retry semantics remain unapproved for real users. Dedicated encrypted delivery requires separately owned
keys, access separation, rotation, destruction and restore policy. Provider templates/handles require a named provider,
scoped credentials, retention and proven idempotency/reconciliation. Generic SMTP binding supplies none of those contracts.
JWT_SECRET reuse for recovery encryption is not authorized.

Future end-user/production work requires complete delivery, enumeration, abuse, transport/deliverability, custody,
retention/deletion, incident and restore ownership. No purge, replay, retry shortcut or activation default is provided.
