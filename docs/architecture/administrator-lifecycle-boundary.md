# Local administrator lifecycle boundary

## Approved scope

The sole project owner approved a manually invoked local operator contract on 2026-10-11. V51 implements first
administrator bootstrap and recovery when there are zero usable administrators. A usable administrator is a persisted
User with `deleted=false`, `enabled=true`, and `ROLE_ADMIN`. A fresh database legitimately has zero administrators;
registration always creates only `ROLE_USER`. No migration, startup task, replica, or public HTTP request promotes a User.
Additional-administrator management remains deferred. Production operator use remains unapproved.

V53's separately approved [local credential recovery](./local-administrator-credential-recovery.md) preserves an existing
sole ADMIN and requires pre-enrolled independent emergency proof. It neither changes zero-admin eligibility nor adds
privileges to this bootstrap operator.

The owner provisions and revokes a dedicated PostgreSQL login, keeps its password in an owner-only libpq password file
outside Git and CI, and verifies the target UUID against their own locally authenticated account. Every invocation and
retry requires confirmation of the operation, database, and UUID. Confirmation prevents mistakes; database authentication
establishes operator authority. The [local runbook](../operations/local-administrator.md) defines execution and recovery.

## Database authority

Before V51, runtime provisioning grants direct DML on `roles` and `user_roles`. Service authorization alone cannot stop a
runtime login from granting ADMIN through SQL or disabling the final administrator. V51 removes direct role DML grants
and attaches owner-checking role triggers. Repeating a broad public-schema DML grant does not bypass those triggers.
Runtime User updates remain available for ordinary application work, with database guards enforcing final-administrator
continuity, immutable UUID/email, permanent retirement, and monotonic credential versions. Password replacement and
disablement must advance the credential version. The existing commerce and User service locks remain in use.

`public.register_role_user(text,text,text,text)` is a narrowly constrained `SECURITY DEFINER` function. It generates a new
UUID, inserts the User, and assigns exactly the seeded `ROLE_USER`; it accepts neither an existing UUID nor a role.
Java retains normalization, BCrypt hashing and validation, and existing uniqueness handling. PUBLIC execute is revoked;
runtime provisioning grants execution without granting role DML.

The private `administrator_lifecycle` schema contains the trusted operator configuration, promotion function and success
evidence. PUBLIC has no access. The runtime has no schema usage or promotion execution. The configured operator has
CONNECT, private-schema USAGE, execution of the single promotion function, and evidence SELECT; it has no User/role DML
or configuration write access. It has no role memberships, object ownership, or elevated PostgreSQL attributes.
The function checks `session_user`, rather than a supplied actor label or `current_user` changed by `SET ROLE`, against
owner-controlled configuration and checks both the current database and the explicitly supplied local environment.
Flyway owns the objects but is not the configured operator and cannot use the promotion function as an operator.

All definer functions use a fixed `pg_catalog` search path and schema-qualified application relations. Database owners,
the migration owner and superusers remain trusted schema authorities: they can replace functions, disable triggers or
change ACLs. This foundation cannot protect against their deliberate interference. Runtime credentials also retain broad
ordinary application-data privileges; these controls prove role-grant and administrator-continuity boundaries rather
than general database isolation from a compromised runtime.

## Transactions and lock order

The administrator serialization domain is PostgreSQL's transaction-scoped two-integer advisory key `(1094995278, 1)`.
Its key space is separate from the existing bigint User/commerce advisory locks. The shared order is:

1. Administrator domain lock.
2. Existing per-User advisory lock `hashtextextended(uuid::text, 0)`.
3. Protected reads and User row locks/mutations.

User-writing services acquire the global lock before their existing per-User lock and repeat the active lookup. Commerce
operations that do not mutate Users retain their existing User lock and do not acquire the administrator lock.
Registration serializes role creation through the same domain. Statement-level triggers acquire it before direct SQL
User updates/deletes or role mutations can acquire row locks. Direct SQL guards enforce continuity under that global
lock; they do not reproduce the application commerce protocol.

These transitions require READ COMMITTED. Higher isolation is rejected because a snapshot obtained before waiting could
miss a committed administrator transition. After the global lock, volatile PostgreSQL functions read current committed
state. Multi-row updates are guarded too: removing two administrators in one statement cannot leave zero usable admins.

Promotion first validates the authenticated local operator, then takes global and target locks, checks for any usable
administrator, and locks/revalidates an existing enabled, non-retired target. It preserves all existing memberships, adds
ADMIN, advances `credential_version`, and inserts evidence in one transaction. Overflow, timeout, rejection or rollback
leaves no partial membership, version change or evidence. Competing promotions serialize: the loser observes the winner
and rejects. Concurrent disablement/retirement on different admins likewise retains at least one usable administrator.

Self-action and action on another administrator use the same invariant. The service returns the existing business-error
format for a continuity conflict; raw runtime SQL is also rejected. A disabled ADMIN cannot be re-enabled from a
zero-admin state through runtime operations. Recovery instead promotes a separately verified eligible User. Retirement
never reverses, and a disabled or retired target is never eligible for promotion.

## JWT, evidence and replay

Promotion increments the existing credential version atomically with membership assignment. Every bearer request reloads
persisted authorities and the version, so previously issued JWTs fail after promotion. A new login produces a token
matching the current version and authorities. No independent token-revocation subsystem is introduced.

`promotion_evidence` records a receipt UUID, target UUID, database-authenticated login, database, fixed operation
`ZERO_ADMIN_PROMOTION`, timestamp and before/after credential versions. It deliberately has no cascading User foreign key.
It contains no password, JWT, email or arbitrary actor string. The owner/operator can read/export it; UPDATE, DELETE and
TRUNCATE are rejected, including ordinary owner statements. There is no deletion command, automatic retention period,
or guaranteed evidence surviving a malicious schema owner. Existing workflow histories have different actor and
transaction semantics and are not reused for identity-control evidence.

Every retry is a newly confirmed operation and rejects while any usable administrator exists. On uncertain connection
outcome, inspect evidence and persisted state before retrying. Rejected-attempt errors are safe, but this approved local
contract provides no independently durable rejection ledger: rows in a rolled-back transaction would not survive.
Database/application logs must not be represented as such a guarantee.

Full backups preserve users, memberships, versions, configuration, functions, ACLs and evidence at their recovery point.
They do not contain cluster-global logins or external credentials. Restore never authorizes replay or invents an admin.
The owner reconstructs identities separately, verifies ownership/ACLs and the recovered admin state, and explicitly
configures the restored local database before a newly confirmed recovery attempt. A copied configuration for a different
database name fails closed. Restoring an older backup can restore older credential versions and roll back evidence;
local recovery must rotate JWT signing material before serving requests to prevent old tokens becoming valid again.

## Compatibility and remaining production decisions

V51 is coordinated maintenance, not rolling-safe: the preceding registration writer directly inserts `user_roles`.
Stop old writers before migrating, start the new application after validation, and never downgrade the schema. Historical
Flyway migrations are unchanged. Local provisioning and restore procedures keep role DML revoked; database triggers also
survive broad public-schema grant refreshes and later migrations.

Real PostgreSQL tests prove transaction rollback, observed advisory-lock blocking/order, concurrent promotions/removals,
least privilege, registration, JWT freshness and dump/restore behavior. CI proves those repository invariants. It cannot
establish external credential custody, target identity proof, backup security, independent audit authority or production
operator readiness.

Production requires a separate deployment security review covering operator credential lifecycle and IAM, target proof,
multi-operator approval/quorum and break-glass authority, durable rejected-attempt evidence, evidence export/retention and
privileged deletion ownership, backup/PITR and credential reconstruction, external audit and incident response. No local
approval or CI result authorizes those missing controls.
