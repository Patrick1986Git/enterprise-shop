# Local sole-ADMIN credential recovery

## Approved contract

On 2026-10-12 the sole owner explicitly approved the nine-point local contract and its trusted offline Java validator,
independent emergency proof, ten-minute one-use authorization, successful-operation evidence, rejected-attempt audit
limitation, post-use session revocation and restore-time invalidation. This approval is separate from V51 and V52.
Production, public enrollment/reset APIs, mail, CI operator execution and automatic activation remain unsupported.

V53 creates a private `administrator_recovery` schema without creating or enabling a login. Flyway owns its objects.
The runtime and PUBLIC receive no access. The existing V51 operator, its configuration and promotion grants are unchanged.
The Java operator package has no Spring beans and no application callers; ArchUnit enforces that separation.

## Enrollment and independent authority

The owner first configures a distinct, non-owner, membership-free reset LOGIN with no elevated attributes. Only an
authenticated trusted PostgreSQL superuser session can configure, enroll, authorize, revoke or invalidate restored
authority. These capabilities are never granted to the reset operator. Administrative role-changing commands use invoker
authority; narrowly constrained data operations use migration-owned definer functions with `pg_catalog` search paths.

Enrollment is deliberately performed before password loss. The Java tool takes the shared lifecycle locks, reads the
current sole ADMIN's hash through the owner-only enrollment function, and verifies the current application password with
the repository BCrypt implementation before enrolling, in the same transaction. Sensitive owner operations also require
PostgreSQL's recorded password-authenticated identity to match session_user. A surviving JWT, UUID, reset login or
runtime SQL access is not an enrollment substitute. The owner session/tool is trusted to perform this check; schema
administrators can bypass it through their existing privileges. Existing active enrollment cannot silently be replaced.
Replacement requires explicit revocation and renewed account control.

Java SecureRandom supplies 32 independent random bytes. The canonical external format is
`v1.<lowercase UUID>.<unpadded Base64url of 32 bytes>`. The UUID is a selector, not authority. Only the protected terminal
reveals the new proof after commit; the tool never saves it. The owner must keep it privately outside Git, CI and logs.

The verifier is SHA-256 over UTF-8 `enterprise-shop/local-admin-emergency/v1`, enrollment UUID, target UUID, database name
and `local`, each followed by a newline, then the decoded 32-byte secret. It is separate from JWT keys and V52's purpose.
Only this verifier and non-secret lifecycle metadata persist. Runtime/reset identities cannot read it. Supplying the
stored digest as the proof does not verify. PostgreSQL comparison examines all 32 digest bytes; no recovery secret,
application password, hash or JWT is included in input-bearing error messages.

The enrolled secret has no ten-minute age limit: it remains usable until committed consumption or explicit revocation.
With valid proof, only the trusted owner session creates a fresh authorization bound to target UUID, database/local
environment, configured reset login and current expected credential version. Exactly ten minutes of PostgreSQL time
govern that authorization. New issuance revokes previous pending authorization without consuming the emergency secret.
Expired/revoked/stale authorization never executes. The reset operator cannot authorize even when it holds a proof.

## Reset, concurrency and evidence

Reset requires both a separately authenticated reset login and the still-valid emergency proof. The operator can execute
only the constrained reset function and read success receipts; it cannot enroll, configure, authorize, inspect hashes or
verifiers, mutate Users/roles, or perform V51 promotion. Function checks use session_user and repeat authority validation
after waiting, including exclusion of a login currently configured as V51 operator. PostgreSQL system_user must identify
the same password-authenticated session login; trust-authenticated sessions and SET SESSION AUTHORIZATION impersonation
cannot satisfy this check. Host authentication still requires owner configuration.

Every command uses READ COMMITTED. The order is administrator advisory `(1094995278,1)`, User advisory
`hashtextextended(uuid::text,0)`, fresh authoritative User/role eligibility, User row lock, recovery state and atomic commit.
Exactly one enabled, non-retired ADMIN must exist, and it must match the immutable target. No role, identity, account-state
or continuity change is allowed. Higher isolation and stale expected versions fail closed.

The tool reuses PasswordRecoveryReplacement validation and the current default BCryptPasswordEncoder: nonblank,
8–72-character replacement, at most 72 UTF-8 bytes and exact repeated confirmation, without trimming/normalization.
The database checks compatibility with the current `$2a$10$` encoding; it cannot infer plaintext policy from a hash.
The owner explicitly accepted the trusted validator/operator boundary. Changing the application BCrypt configuration
requires corresponding reviewed compatibility work.

Password, exactly one credential-version increment, normal update timestamp, authorization/secret consumption, V52 open
challenge revocation and one append-only receipt commit together. A final database-clock guard rejects expiry after the
User mutation and rolls all changes back. Old JWTs fail subsequent per-request version validation; no auto-login occurs.
Previously authenticated in-flight work is not canceled. V52 remains unwired and ADMIN-excluding.

Competing resets serialize. An authenticated password change winning first invalidates expected-version authority; reset
winning first invalidates the old current password. Final-admin disablement/retirement stays forbidden. Any changed
eligibility is rechecked after waiting. V51 promotion cannot run while a usable ADMIN remains.

Receipts record authorization ID, target UUID, database-authenticated operator, database, fixed RESET_SOLE_ADMIN operation,
time and before/after versions. They contain no credential material or arbitrary actor label and have no cascading User
FK. History is immutable/terminal and has no deletion command or retention cutoff. This is database-point durability,
not protection against malicious owners or loss beyond a restored snapshot. Rolled-back attempts have no independently
durable ledger. Database/application logs are not presented as that ledger.

## Sensitive transport and trusted boundaries

The wrapper requires stdin/stdout/stderr terminals and removes inherited JVM, classpath, loader and libpq overrides.
Java reads secrets/passwords through Console, never command arguments or environment variables; JDBC uses prepared
extended-protocol parameters. PostgreSQL driver logging is disabled. Owner commands set transaction-local safe logging;
fresh reset sessions inherit owner-provisioned role/database settings. A non-secret preflight checks those settings before
binding sensitive input. Functions also reject unsafe logging, pgAudit statement auditing and enabled auto_explain.
Statements/duration sampling and parameter logging are disabled, and terse server errors exclude failing-row details.
External host monitoring, core dumps, malicious JVM agents, database owners and custom audit extensions remain trusted
operational boundaries; these controls do not establish protection against a compromised local host.

The runtime still has its existing ordinary User UPDATE access. V51 requires password/version consistency rather than
independent ownership proof for arbitrary runtime SQL. This tool neither expands that access nor claims to contain every
compromised-runtime password write. Migration owners, database administrators and superusers can replace functions,
triggers, ACLs or enrollment data and remain trusted.

## Failure, revocation and restore

Failures and rollback do not consume either authority. On uncertain completion, inspect the operation receipt and current
state before a newly confirmed retry. A consumed proof cannot reset again. Unknown enrollment completion can strand a
verifier whose secret was never displayed; while the application password is still known, inspect metadata, explicitly
revoke that enrollment and enroll anew. Neither missing proof nor loss of both secrets has a supported bypass.

After success/reconciliation and new login, the owner must revoke operator capability, disable LOGIN, terminate existing
sessions and dispose of external operator credentials. The command first commits NOLOGIN/privilege/authority revocation,
then terminates and verifies remaining sessions in a second owner transaction. This prevents a pre-commit login race;
termination failure does not restore revoked capability. NOLOGIN alone is insufficient. Revocation is explicit; interrupted
commands require owner reconciliation and completion of the revoke step. No automatic privilege elevation/removal worker
is introduced.

Restore into isolation with traffic/workers stopped. Invalidate every restored pending authorization and every unconsumed
enrollment verifier, even with the same database name, because rollback can resurrect previously spent proof. Remove
stale operator access/sessions. Preserve terminal history and receipts, validate Flyway/owners/ACLs, reconstruct external
identities/credentials separately, and replace JWT signing keys without retaining pre-restore keys for verification.
JWT rotation does not invalidate emergency proof or V52 opaque challenges; revoke open V52 challenges separately.
Fresh enrollment requires current account control after deliberate operator reconfiguration. If that control is lost,
restore does not create a fallback reset right.

Production requires separately approved IAM, identity/approval controls, credential and key custody, independent audit,
retention and incident ownership, and backup/PITR/RPO/RTO readiness. Local approval and CI do not supply these controls.
