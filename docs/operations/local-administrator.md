# Local administrator bootstrap and zero-admin recovery

## Scope and prerequisites

This runbook is approved only for the sole owner's local installation. Production use requires the separate review in
the [administrator lifecycle boundary](../architecture/administrator-lifecycle-boundary.md). Apply V51 during coordinated
maintenance with old writers stopped. Keep PostgreSQL administrator, migration, runtime and operator logins distinct.
Use a local terminal with Python 3.9+ and PostgreSQL 18 `psql`; do not run the operator command from CI or replica startup.

The owner controls the local machine and database administration. Create a fresh dedicated operator LOGIN manually using
the PostgreSQL administrative session, with `NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS` and
no memberships or owned objects. Set its credential interactively with `psql`'s `\password` command so the password is
not embedded in SQL, shell history or arguments. Confirm the local server's authentication rules require this identity's
credential; do not treat a trust-authenticated socket or the CLI confirmation as proof of authorization.

Store the operator credential in a regular libpq password file owned by your OS account with mode `0600`, outside the
repository and all CI/build configuration. Use a specific host/port/database/login entry rather than a wildcard. Create
and edit that file with your local secret-handling procedure; never paste it into chat, a PR, logs or an artifact.
Provision administrator credentials separately; the scripts accept no password argument. Password-file custody and
local OS/database administration are owner responsibilities beyond repository enforcement.

## Configure the operator

After V51, run the configuration file manually through the local PostgreSQL administrative identity. Select explicit
local connection arguments and your administrative password file; replace the example names with the real local names:

```bash
PGPASSFILE=/absolute/private/admin.pgpass psql -X --no-password \
  --host 127.0.0.1 --port 5432 --username postgres --dbname enterprise_shop_dev \
  --set=operator_login=shop_local_operator --set=runtime_login=shop_dev \
  --set=migration_login=postgres --set=expected_database=enterprise_shop_dev \
  --file=scripts/configure-local-admin-operator.sql
```

Where local Flyway uses the PostgreSQL administrator as its schema owner, the example `migration_login=postgres` is
appropriate; runtime and operator must still be separate. For a distinct migration login, use its actual name. The
configuration checks that the private schema is owned by the named migration identity and that the selected database,
operator attributes and separation are valid. It grants only promotion execution and evidence read access, and records
the trusted local database/login mapping. No migration automatically creates or configures this operator.

## Verify and promote the target

Register your owner account normally and log in locally. Read its immutable UUID from the authenticated current-account
response and compare it to the intended owner's identity and selected database. Do not select by email alone, trust a
UUID received from someone else, or copy a production identity. The target must exist, be enabled, and not be retired.
If an administrator is already usable, use normal authorized operations; this command cannot add another administrator.

Run the offline command with explicit bounded timeouts and the operator password file:

```bash
python3 scripts/local-administrator.py \
  --host 127.0.0.1 --port 5432 --database enterprise_shop_dev \
  --operator shop_local_operator --target VERIFIED_USER_UUID \
  --password-file /absolute/private/operator.pgpass \
  --lock-timeout-ms 3000 --statement-timeout-ms 10000
```

Review and type the exact `PROMOTE_ZERO_ADMIN database UUID` confirmation. Only loopback or a local Unix socket directory
is accepted. The password file is checked without reading or printing its contents; inherited libpq overrides are removed
and `psql` startup files are disabled. The database verifies the authenticated login, local environment, database, target
eligibility and zero-admin state again. Successful output contains only the committed evidence receipt. Log in again:
tokens issued before promotion have a stale credential version.

## Failures, retries and evidence

A failed or uncertain command is not proof of rollback. A disconnect could occur after commit. Inspect evidence through
the operator login and compare the target UUID, time, database and version transition with the attempted operation:

```sql
SELECT id, target_user_id, operator_login, database_name, operation,
       credential_version_before, credential_version_after, recorded_at
FROM administrator_lifecycle.promotion_evidence ORDER BY recorded_at, id;
```

The owner may export that query using `psql \copy` to an owner-only file outside Git. Evidence includes identity UUIDs;
keep exports private and outside ordinary build artifacts. No deletion or retention job is provided. Rejected attempts
do not have guaranteed independently durable evidence. The CLI suppresses driver diagnostics to avoid exposing sensitive
connection details. Every retry requires fresh confirmation and rejects if any usable administrator exists.

## Revoke and restore

After use, the owner may revoke operator login authority. In a trusted administrative session, disable its LOGIN, revoke
private-schema/function/table privileges, terminate existing operator sessions, remove its row from
`administrator_lifecycle.operator_identity`, and remove the external password file using your secret-disposal procedure.
Terminate sessions before relying on revocation: `NOLOGIN` alone does not disconnect existing sessions. Preserve the
promotion ledger. Replacing an operator requires explicit revocation and configuration; the configuration command refuses
to silently replace a different registered login.

After backup restore, keep application traffic stopped. Reconstruct cluster-global runtime/migration/operator identities
from owner-controlled provisioning, without runtime or operator memberships in privileged roles. Restore complete
ownership, ACLs, default privileges, functions/triggers, configuration and evidence; validate Flyway. Inspect administrator
eligibility and credential versions at the recovered point. Rotate JWT signing material before serving requests because
restore can roll back credential versions and revive a previously stale token. No restore command replays promotion.

Revoke any stale operator sessions/capability and deliberately configure the recovered local database using the above
procedure. A changed database name invalidates copied operator configuration until this step. If zero usable admins
remain, verify an eligible target against a new local login and invoke one freshly confirmed recovery. A disabled or
retired former administrator is ineligible; no automatic re-enablement or replay is authorized. If the backup contains a
usable administrator, promotion must reject. Backup loss before a committed promotion can also remove its evidence;
the repository cannot provide audit durability beyond the recovered database point.
