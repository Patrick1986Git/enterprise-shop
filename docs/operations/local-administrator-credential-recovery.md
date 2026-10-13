# Local sole-ADMIN credential recovery

Use this manually only on the sole owner's local Ubuntu installation. The approved authority and limitations are in
[the architecture contract](../architecture/local-administrator-credential-recovery.md). Apply V53 with Flyway; do not edit
V50–V52. Use a verified Java 21 JDK/application JAR and PostgreSQL 18. Follow the existing local-development verification
procedure, including the reviewed Ryuk preparation and `./mvnw -B clean verify`, before operating. CI uses synthetic tests
and must never receive the real operator password, application password or emergency proof.

Keep application traffic/workers stopped during these owner operations. Keep runtime, Flyway, V51 operator and reset
logins distinct. The owner controls local host administration, database authentication, credential custody, PostgreSQL
logging, backups and hard JWT-key revocation. Require credential-based local authentication; trust sockets are not proof.

## Configure the temporary operator

Through your separately authenticated administrative psql session create a fresh reset-only role with
`LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`, no memberships and no owned objects.
Use psql's interactive `\password` under a credential-safe administrative logging policy. Never put its password in SQL
literals, commands, environment variables, source files or artifacts. This tool creates no login or initial credential.

The commands below use the existing local defaults; replace the database/port/login/UUID with verified local metadata.
All database passwords and emergency/application secrets are prompted through the terminal. No secret option exists.

```bash
python3 scripts/local-admin-credential-recovery.py configure \
  --host 127.0.0.1 --port 5433 --database enterprise_shop_dev --login postgres \
  --lock-timeout-ms 3000 --statement-timeout-ms 10000
```

After exact confirmation, supply the administrative login password and the distinct reset/runtime/Flyway/V51 names at
the prompts. For default local Flyway the owner name is `postgres`; runtime is `shop_dev`. Configuration grants only
private reset execution and receipt reads and sets safe logging defaults for new reset sessions. It rejects identity
overlap, memberships, ownership/elevated attributes and silent replacement of an active different operator.

## Enroll while the application password is still known

Verify the ADMIN's immutable UUID against your own freshly authenticated local account. Exactly one usable ADMIN must
exist. Enrollment requires both the trusted administrative session and the current ADMIN application password.

```bash
python3 scripts/local-admin-credential-recovery.py enroll \
  --host 127.0.0.1 --port 5433 --database enterprise_shop_dev --login postgres \
  --target VERIFIED_ADMIN_UUID --lock-timeout-ms 3000 --statement-timeout-ms 10000
```

Confirm the action/database/UUID, enter the administrative password and then the current application password. After
commit the tool deliberately reveals the emergency proof once through the terminal. Keep it in your private recovery
custody outside Git, logs and CI. The tool saves no copy; losing it is fail closed. Its first UUID component is the
non-secret enrollment selector. To replace enrollment, use `revoke-secret` with the verified target and selector, then
enroll again with current application control. Runtime/reset credentials cannot perform these steps.

## Authorize and reset

When the application password is lost, obtain the target's current non-secret credential version through the trusted
owner session, for example `SELECT id, credential_version FROM public.users WHERE id = 'VERIFIED_ADMIN_UUID';`.
Do not select or print password hashes. With the enrolled proof and owner session, issue fresh bounded authority:

```bash
python3 scripts/local-admin-credential-recovery.py authorize \
  --host 127.0.0.1 --port 5433 --database enterprise_shop_dev --login postgres \
  --target VERIFIED_ADMIN_UUID --version CURRENT_VERSION \
  --lock-timeout-ms 3000 --statement-timeout-ms 10000
```

The committed operation UUID identifies the authorization and conveys no bearer right. Exactly ten minutes from
PostgreSQL issuance time govern execution. Fresh authorization supersedes earlier pending authority and leaves the
emergency proof unconsumed. An expired authorization needs a newly confirmed issuance using still-valid proof.

Invoke reset through the configured reset login, supplying the same target/version:

```bash
python3 scripts/local-admin-credential-recovery.py reset \
  --host 127.0.0.1 --port 5433 --database enterprise_shop_dev --login shop_local_reset \
  --target VERIFIED_ADMIN_UUID --version CURRENT_VERSION \
  --lock-timeout-ms 3000 --statement-timeout-ms 10000
```

After exact confirmation, enter the reset login password, emergency proof, non-secret operation UUID and replacement
application password twice. It uses existing validation/BCrypt without normalization. Success outputs only the committed
receipt. The proof and authorization are consumed together only by successful commit; roles and identity remain intact.
Log in normally with the replacement. Pre-reset JWTs no longer authenticate.

## Reconcile, revoke and restore

A failure/disconnect is not proof of rollback. Use `status` with explicit local connection/timeouts and the authorization
UUID prompt, preferably through the owner session. Inspect the matching secret-free receipt and current User version/state
before any confirmed retry. No receipt at an older recovery point does not prove the original reset never committed.
Never blindly retry/replay, treat a typed confirmation as approval, or invent a rejection ledger from application logs.

After success and reconciliation, run `revoke-operator` with the administrative login and explicit local connection/timeouts.
It disables LOGIN, revokes reset privileges, invalidates pending authorizations and terminates existing operator sessions.
Then dispose of external operator credentials using the owner's local procedure. Preserve receipts and history. Fresh use
requires deliberate fresh credential provisioning, LOGIN enablement and configuration. The V51 operator is untouched.

After any restore, while traffic/workers remain stopped, run `invalidate-restored` with the administrative login and the
explicit restored database connection/timeouts. It revokes operator capability/sessions plus pending authorization and
unconsumed enrollment verifiers. Do this even for a restored database with the same name. Revoke open V52 challenges with
the existing maintenance procedure and replace JWT material, removing pre-restore active/previous verification keys.
Restore roles/owners/ACLs/functions and Flyway history faithfully; external credentials/logins are not in pg_dump.
Validate before restart. Fresh enrollment requires renewed current application control after operator reconfiguration.

If no proof was enrolled, proof is lost/revoked/consumed, or both application password and proof are lost, recovery is
unsupported. After restore, an invalidated proof cannot be reused. Database administrator SQL capabilities are not a
supported bypass. Public/end-user and production recovery require separate authorization and operational review.
