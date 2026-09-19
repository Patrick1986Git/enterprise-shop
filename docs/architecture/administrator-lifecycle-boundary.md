# Application administrator lifecycle boundary

## Audit boundary and decision

This decision was audited against protected `master` at
`7359ac7a6eabd09d85a6d83dac67fcdf5dda995c`. PR #391 was squash-merged, the protected-head build, Docker validation,
container-security, Pages, and CodeQL checks were successful, and the repository reported no open pull requests or
issues at that boundary. The checked-in JaCoCo baseline was 3,899 covered and 371 missed lines, and 1,042 covered and
209 missed branches.

The repository does not contain enough ownership and audit policy to implement a safe application administrator
bootstrap or recovery mechanism. The selected contract is therefore fail closed: there is no supported production
first-administrator operation, administrator role grant/revoke operation, or zero-administrator recovery operation.
Production must not be represented as administratively ready until the owner decisions below are made and one complete,
reviewed mechanism is implemented. This records a control-plane gap; it is not an instruction to mutate production data
ad hoc.

## Current authority lifecycle

`ROLE_ADMIN` enters a new database only as a role catalogue row in Flyway V1. That row is an application authority; it
is not a PostgreSQL login, database role, schema owner, migration identity, or operator identity. V1 does not create a
User or a `user_roles` assignment. Public registration creates an enabled User and assigns only the seeded `ROLE_USER`.
No production configuration, startup component, deployment/recovery script, service, repository command, or HTTP API
assigns `ROLE_ADMIN` to a User.

Tests construct administrators directly through fixtures or entity role assignment. Those test arrangements are not a
production bootstrap contract. Similarly, `scripts/bootstrap-app-role.sh`, `scripts/db-setup.sql`, and the Compose
`database-role-bootstrap` task create and grant a local PostgreSQL runtime login; despite the word “bootstrap”, they do
not grant the application `ROLE_ADMIN` authority to any User.

Consequently:

- a fresh production installation has no repository-supported way to obtain its first application administrator;
- there is no supported normal path for creating an additional administrator;
- administrators can retire or disable themselves or another administrator, including the last usable administrator;
- a deployment can contain no active ADMIN Users, or only disabled or retired ADMIN Users; and
- when no usable administrator remains, login and ordinary authenticated functionality continue, but every ADMIN HTTP
  and protected ADMIN actuator operation is unreachable through application authorization.

For this analysis, a **usable administrator** is a persisted User who is not retired (`deleted=false`), is enabled, and
has the persisted `ROLE_ADMIN` assignment. The application does not currently enforce the invariant that at least one
such User exists.

## Threat model and alternatives

### Initial bootstrap and deployment-time one-shot

A startup promotion driven by an email or other configuration value is rejected for now. The repository has no owner for
the bootstrap secret/configuration, no evidence format, no cleanup or expiry contract, and no rule for retry after a
partial deployment. Running the operation automatically on every replica would turn a one-shot into a replayable
production capability and would require PostgreSQL serialization to resolve concurrent replicas. Merely checking that
no administrator exists before inserting a role assignment is a race. Keeping the configuration available after success
would be a permanent promotion backdoor.

A future one-shot design would have to run separately from ordinary replica startup, identify exactly one existing
active and enabled User, serialize all bootstrap/recovery and administrator-removal operations through one stable
PostgreSQL lock domain, commit the role assignment and durable success evidence atomically, reject an existing usable
administrator according to an explicit replay policy, remove its capability after success, and fail without partial
mutation. Persisted-role reload means promotion would grant ADMIN on the target's next bearer request without issuing a
new JWT. Whether promotion should also increment `credential_version` and require a new login is an unresolved owner
decision, not a technical necessity under the current authorization model.

### Privileged offline/operator command

A narrowly scoped offline command is the strongest candidate for both initial bootstrap and zero-administrator recovery,
because it does not require the authority that it is creating and need not expose a permanent HTTP surface. It is not
implemented because the repository does not identify the authorized production operator, approval/quorum process,
credential custody, target identity proof, or immutable evidence and retention contract.

Such a command must use a dedicated, deployment-owned operator identity with only the privileges required by the reviewed
procedure. It must not use the runtime datasource identity. The runtime identity necessarily has ordinary application
DML today, so raw SQL may be practically possible with leaked runtime credentials; that fact is not an authorization or
a supported recovery mechanism. The runtime application must not receive a callable bootstrap capability. The Flyway
identity is for forward schema evolution and must not be repurposed to select or promote a human. A PostgreSQL
administrative identity may provision the narrowly privileged operator identity but should not become the day-to-day
application runtime identity.

The eventual command must lock and validate the target in one transaction, reject missing, retired, or disabled Users,
handle repeat invocation explicitly, write approved evidence in the same transaction as a successful mutation, and leave
no partial assignment after failure. Backup and restore preserve whatever `users`, `roles`, assignments, and evidence
exist at the selected recovery point; restoration must not silently invent a new administrator or replay a one-shot.
Recovery after restore remains a separately approved operator action.

### First registered User becomes ADMIN

This alternative is rejected. Registration is public and the repository proves no private network, deployment gate, or
single-replica boundary around the first request. Concurrent registrations and horizontally started replicas would race,
and an Internet caller could pre-register and take control before the intended operator. A database uniqueness mechanism
could choose one winner but could not establish that the winner is authorized.

### Application role grant/revoke

General role management is deferred. An ADMIN-protected endpoint cannot create the first administrator or recover from
zero administrators. After bootstrap exists it may add operational value, but the repository does not define assignable
roles, actor authorization beyond ADMIN, self-demotion, final-admin behavior, evidence requirements, or whether role
changes require credential-version invalidation. Adding it now would create only a partial control plane.

### Intentionally operator-managed only

Operator-only bootstrap and recovery is the preferred direction, but it is not yet a supported runbook because the
required owner and evidence decisions are absent. Operators must not infer a production procedure from entity methods,
test fixtures, local PostgreSQL bootstrap scripts, or Flyway migrations. Ordinary migrations must never assign a real
human administrator: they run across environments and have neither human identity proof nor one-shot authorization.

## Continuity, concurrency, and self-action

The final-active-admin invariant is not selected in this change. Enforcing it would intentionally change current API
behavior and cannot be made correct with `count(admins) > 1` followed by a later mutation. If the owner selects the
invariant, disable, retirement, and future ADMIN revocation must all share a single PostgreSQL-backed serialization
domain with bootstrap/recovery. The transaction must acquire that lock before reading usable-administrator state and
hold it through mutation/commit. Per-User advisory locks alone serialize actions on one User but do not serialize two
administrators being removed concurrently. No JVM-local lock is acceptable in a multi-replica deployment.

Current compatibility is preserved: an administrator may disable or retire themselves, may act on another
administrator, and may remove the final usable administrator. A disabled administrator cannot enable themselves on a
subsequent request because their next bearer request is unauthenticated. An already committed concurrent self-enable may
win only according to the existing per-User lifecycle-lock ordering. ADMIN revocation, including self-revocation, is
unsupported because no role-mutation operation exists.

## JWT and credential implications

Every bearer request reloads the active User, enabled state, credential version, and persisted roles. JWT role metadata is
not used as current authority. Therefore an out-of-band persisted promotion would grant ADMIN on the next request even to
an otherwise valid token issued before promotion, and a persisted demotion would remove ADMIN on the next request. A
disable transition increments `credential_version`; old tokens fail while disabled and remain stale after enablement.
Retirement remains permanent through application behavior, and the retired email remains reserved. Any future operator
command or role API must preserve those properties and must not derive current roles from token claims.

## Audit evidence boundary

Existing append-only ADMIN action logs are workflow-specific and record committed operational commands. They do not
define an identity-control audit ledger. Administrator bootstrap and recovery require durable evidence, but the
repository does not define the authorized actor identifier for an offline operator, target identity snapshot, operation
and outcome vocabulary, representation of rejected attempts, retention owner, or read authorization. A generic audit
framework is therefore not introduced.

Before implementation, owners must decide whether successful role mutation and evidence must be one transaction (the
recommended baseline), where independently durable failed-attempt evidence is written, and who may retain, read, export,
and delete it. Any database table selected for evidence must be append-only to the runtime identity in the same manner as
the existing protected ADMIN logs; privileged operator and retention powers must be separately governed.

## Required owner decisions

Implementation remains blocked until the responsible security/product/deployment owners provide all of the following:

1. the accountable actor and approval/quorum for first bootstrap and zero-administrator recovery;
2. the target User identity-proof procedure and whether a disabled User may be recovered or must first follow a separate
   approved enablement action;
3. whether normal additional-admin management exists, who may perform it, and which roles are assignable;
4. whether at least one usable administrator is a mandatory invariant;
5. self-disable, self-retirement, self-demotion, and administrator-on-administrator policy, including compatibility timing;
6. whether promotion or demotion increments `credential_version` in addition to immediate persisted-role freshness;
7. bootstrap/recovery replay, expiry, break-glass, rollback, and post-use credential-removal rules;
8. the dedicated operator database identity, least-privilege grants, credential custody, and separation from runtime and
   Flyway identities; and
9. evidence fields, successful and rejected outcome semantics, transactional boundary, read authorization, export,
   retention, and deletion ownership.

After those decisions, one change must implement the complete selected path with PostgreSQL/Testcontainers coverage for
one-shot behavior, concurrent attempts and removals, target lifecycle races, rollback/no-partial-mutation, zero-admin
recovery, authorization freshness, and stale-token/non-resurrection behavior. Until then, production readiness must fail
closed rather than exposing a partial administrator backdoor.
