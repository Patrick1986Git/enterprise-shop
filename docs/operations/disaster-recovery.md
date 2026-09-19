# PostgreSQL disaster recovery and restore readiness

## Decision and ownership boundary

Enterprise Shop uses a **combined model (Outcome C)**:

- the production deployment owner must provide backup scheduling, retention, encryption, access control, integrity monitoring, geographic-failure protection, WAL archiving and point-in-time recovery (PITR) needed to meet its selected recovery-point objective (RPO) and recovery-time objective (RTO); and
- this repository defines a portable logical-backup baseline and the application-specific restore checks that every deployment must rehearse.

The repository contains no production database platform or storage configuration. It therefore cannot select honest numeric RPO/RTO values or configure physical backups. Before production approval, the deployment owner must record the selected RPO and RTO, backup/PITR technology, backup and WAL frequency, retention and deletion rules, encryption and key ownership, restore location and PostgreSQL compatibility, responsible operators, alerting, and dated successful rehearsal evidence. An untested statement that backups are enabled is not readiness evidence.

Application write rate, database size, network and restore throughput, Flyway migration duration, and the worker settings for reservation expiry and notification/outbox processing affect those choices. The deployment must measure them; this repository does not supply substitute values.

## Authoritative recovery set

### PostgreSQL application data

The recovery point must contain a transactionally consistent set of:

- users, roles, and user-role membership;
- categories, products, product images and reviews, including stock, ratings, search vectors, and optimistic-lock versions;
- carts and cart items;
- orders and immutable order-item/user snapshots, discounts, checkout idempotency keys, reservation expiry timestamps, and reservation work/claim/recovery state;
- payments and provider payment identifiers;
- Stripe webhook event identifiers and processing state used for idempotency;
- outbox payloads, attempts, versions, next/last attempt, processed, requeue, and dead-letter state;
- notifications and delivery attempt, claim/lease, retry, sent, requeue, and failure state; and
- notification, outbox, and reservation ADMIN action histories.

The [operational data-retention contract](./data-retention.md) currently authorizes no age-based purge. Any future approved purge changes this authoritative recovery set and must be reflected in backup expectations and restore rehearsals.

### Schema and database-local state

Recovery also requires `flyway_schema_history`; sequence/identity values; tables and columns; foreign keys, checks, uniqueness constraints, indexes, triggers, and functions; the `uuid-ossp` and `unaccent` extensions; the Polish text-search dictionary/configuration and product search artifacts; database and schema ownership; object ownership and ACLs; owner-specific default privileges; and the relationship between the administrative, migration, and runtime roles.

PostgreSQL roles are cluster-global rather than part of a single-database `pg_dump`. Role definitions, memberships, login attributes, and credentials must be reconstructed by deployment-owned provisioning. Secrets (database, JWT, Stripe, and SMTP credentials), secret-manager state, certificates, application images/configuration, product image objects referenced by URLs, and Stripe's provider-side objects/events are external recovery sets. Restoring PostgreSQL restores none of them.

## What the repository currently provides

The local Compose service builds PostgreSQL 18 with required Polish Hunspell files and stores its cluster in the versioned `enterprise_shop_postgres18_volume`. That named volume is local persistence, not a production backup. The PostgreSQL 16-to-18 procedure in `local-development.md` is a local logical version migration and is not a production DR policy.

Flyway owns schema history and production startup uses the distinct migration identity with automatic baselining disabled. Hibernate then uses `ddl-auto: validate`; a startup validator rejects runtime ownership of the V45-protected history tables. The local role bootstrap provisions only the local development identity. The ownership-transfer script is narrowly for a reviewed legacy runtime-to-migration ownership conversion; it is not a general backup or role-recovery tool.

The repository does not configure production backup scheduling, WAL archiving, PITR, replicas, retention, encryption, cross-region copies, or backup storage. Those capabilities must not be inferred from Docker Compose or Testcontainers.

## Backup technology analysis

### Logical dumps

Both plain SQL and custom-format dumps can preserve rows, schema definitions, sequence values, triggers, functions, text-search objects, extension declarations, object ownership metadata, ACLs, and default-privilege commands when created without filters. Neither single-database format contains cluster-global role definitions/passwords or provider state. Database creation/ownership is not complete unless explicitly included or established on the target, and external Hunspell dictionary files are never embedded.

A plain SQL dump is restored by `psql`, is less selectively inspectable, and embeds creation/data/ACL statements in execution order. A custom-format dump (`pg_dump -Fc`) is the repository baseline because `pg_restore` can list, select, order, clean, and parallelize entries. This is a portability/rehearsal baseline, not evidence that logical dumps meet a deployment's RPO/RTO.

An owner-preserving restore replays recorded owners and ACLs, but succeeds only after all referenced roles exist and the restoring identity may assign those owners. A `--no-owner` restore makes created objects owned by the restore session; it is safe only when that session is the migration identity (or ownership is subsequently and completely reassigned by an administrator). Running `--no-owner` as the runtime identity violates the production boundary. `--no-owner` does not reconstruct role attributes, memberships, database/schema ownership, or owner-specific default privileges; ACL replay can still refer to roles unless separately suppressed, which is not the normal production contract.

Use PostgreSQL 18 client tools for the PostgreSQL 18 baseline. A dump tool must not be older than its source server. Restore first to the same supported major. A reviewed logical upgrade to a newer supported major may be rehearsed with that target's `pg_restore`, but application, Flyway, extension, and text-search validation remain mandatory. Never open a PostgreSQL 18 data directory with another major server.

### Physical backup and PITR

Only the deployment platform can provide a coherent physical backup plus continuous WAL/archive chain. Physical/PITR recovery is the preferred basis when the selected RPO requires recovery between logical dumps. It must recover the entire mutually compatible cluster state according to the platform contract, including system catalogs and roles, and must prove timeline/WAL completeness, encryption, retention, corruption detection, and restore isolation. Physical files are tied to PostgreSQL major version, build/platform, and provider procedure; a volume copy taken without a PostgreSQL-supported physical-backup protocol is not a backup.

## Consistent backup creation

Use a dedicated, least-privilege backup identity approved by the deployment owner and a PostgreSQL 18 `pg_dump`. Supply credentials through a protected password file or the platform's secret mechanism, not a password in a command argument or logged shell tracing. Capture tool/server versions, source identifier, UTC start/end, selected recovery policy, and a cryptographic digest in the protected backup system.

The conceptual full-database command is:

```bash
pg_dump --format=custom --file="$PROTECTED_BACKUP_PATH" \
  --dbname="$DATABASE_BACKUP_URL"
pg_restore --list "$PROTECTED_BACKUP_PATH" >/dev/null
```

Do not add table filters. `pg_dump` takes a transactionally consistent MVCC snapshot while normal writes continue, so routine logical backup does not require application downtime. Transactions committed before the snapshot are represented; transactions not visible at that boundary are absent as a unit. A dump from a replica is acceptable only if the deployment proves replica consistency and lag satisfy the RPO and keeps the session alive; record the effective recovery point. DDL must be operationally excluded or coordinated during the dump.

At the snapshot boundary, an order and its transactionally inserted outbox row remain consistent. Persisted attempts, dead letters, webhook idempotency rows, and payment identifiers reflect the same snapshot. Claims/leases may restore as claimed; lifecycle recovery must be allowed to reclaim them after their stored lease/timeout. External side effects can nevertheless be newer than the snapshot.

## Restore procedure

Restore only into an isolated, empty target. Keep application traffic and all workers stopped until verification succeeds.

1. **Authorize the recovery point.** Identify the incident, desired time, backup/WAL source, expected PostgreSQL and application/Flyway versions, digest, and external-system divergence window. Preserve the damaged source for investigation where possible.
2. **Verify prerequisites.** Verify the backup digest and `pg_restore --list`; use a supported PostgreSQL 18 target/custom image with the Polish dictionary files present. Abort if the dump is unreadable, source/tool versions are incompatible, required extensions/files cannot be installed, or the intended roles cannot be reconstructed.
3. **Provision identities outside the dump.** Through deployment-owned privileged provisioning, recreate the administrative, migration, and runtime roles with the production names. The runtime role must be `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION`, must not inherit or belong to migration/administrative roles, and must use separately restored secret-manager credentials.
4. **Prepare ownership.** Create the target database and application schema owned by the migration identity (or arrange an administrative restore that can assign that owner). The runtime identity must not own the database, schema, tables, sequences, functions, extensions, or text-search objects.
5. **Restore.** Prefer an owner-preserving restore when the original role names have been provisioned. Alternatively, restore with `--no-owner` while authenticated as the migration identity, never runtime, then use reviewed administrative SQL to establish database/schema ownership. Do not use the local bootstrap as production provisioning. Restore ACLs and explicitly reapply the current runtime grants and `ALTER DEFAULT PRIVILEGES FOR ROLE <migration>` contract described in `database.md`.
6. **Verify ownership before startup.** Confirm all application objects, especially the three ADMIN history tables and `reject_runtime_admin_action_log_mutation()`, are migration-owned; runtime owns no application object; database/schema ownership is correct; current table/sequence/function grants exist; and future objects inherit runtime grants from migration-owned default privileges. Abort rather than broadening runtime privileges to make startup pass.
7. **Preserve Flyway history.** For an exact-version restore, retain `flyway_schema_history` unchanged and run Flyway validation. For an older backup with newer application code, retain its historical table, deploy the newer application's migration identity, let Flyway validate checksums and apply only pending forward migrations, then let Hibernate validate. Never edit historical migrations, manually rewrite history, enable automatic baselining, or attempt a database downgrade for an application rollback.
8. **Start without traffic.** Start one application instance with production Flyway credentials. Require successful Flyway validation/migration, Hibernate validation, ownership validation, readiness, and authenticated smoke checks before starting additional instances/workers and restoring traffic.
9. **Validate data and behavior.** Compare recorded critical counts/checksums or synthetic rehearsal markers; inspect users, products/stock/version, orders/items, payments/provider IDs, webhook events, reservation work, outbox, notifications, histories, and Flyway history. Verify sequence next values cannot collide. Verify ordinary runtime DML, runtime ADMIN-history insert/select, and SQLSTATE `42501` for runtime update/delete of each protected history table. Verify the migration identity can perform reviewed maintenance/migration work.
10. **Reconcile external effects.** Compare the recovery point with Stripe and other external systems before enabling payment flows. Record evidence and the privileged restore as an administrative security event.

Missing or inconsistent Flyway history, checksum mismatch, unsupported PostgreSQL version, absent dictionary/extension support, incomplete roles/owners/default privileges, failed constraints/triggers, unsafe sequences, or unexplained Stripe divergence are stop conditions—not reasons to repair history or relax validation.

Restore preserves application `ROLE_ADMIN` assignments only as of the selected recovery point. It must not promote a
User, enable a disabled User, or resurrect a retired User as a side effect. If the restored point has no usable
administrator, ADMIN operations remain unavailable: the repository has no supported zero-administrator recovery command.
Do not substitute the runtime, Flyway, or restore identity for the unresolved operator contract. The fail-closed recovery
boundary and required owner decisions are recorded in the
[administrator lifecycle boundary](../architecture/administrator-lifecycle-boundary.md).

## Rehearsal contract

The deployment owner must run a restore rehearsal at a cadence derived from its RPO/RTO and after material PostgreSQL, backup-platform, role/ownership, extension/text-search, or migration changes. Because this repository has no production platform and this environment may not have Docker, no provider-shaped automation is added to every PR. The existing PostgreSQL/Testcontainers migration and production-identity tests remain component evidence, not a substitute for end-to-end restoration.

A deterministic rehearsal must use synthetic data only: create an isolated PostgreSQL 18 source using the repository's custom image; apply all Flyway migrations as a synthetic migration role; add representative user/catalog/stock/cart/order/item/payment/webhook/reservation/outbox/notification/history records; take a full custom dump; restore to a fresh isolated target with separately provisioned synthetic roles; then execute steps 6–9 above. Retain only non-sensitive evidence: source/target/tool versions, commit/application version, dump digest, duration against the selected RTO, Flyway version/checksum result, invariant results, and operator/date. Never upload a realistic production dump to ordinary GitHub Actions artifacts.

Success requires all of these invariants: Flyway has no inconsistency; representative critical rows and state survive; constraints/indexes/functions/text-search objects and sequences work; runtime is a non-owner with ordinary DML; migration owns protected objects; default privileges target runtime; V45 triggers exist; runtime update/delete on all protected histories fails with `42501`; migration maintenance works; and application readiness succeeds. A dump that can be listed but has not passed these checks is not proven restorable.

## Scenario decisions

| Scenario | Recovery decision |
| --- | --- |
| Accidental deletion | Prefer PITR to a point before deletion when available; otherwise restore the latest qualifying logical backup to isolation and accept only the deployment's declared RPO loss. |
| Bad forward migration | Stop writers, preserve evidence, and restore/PITR to isolation. Prefer a reviewed forward repair when safe; do not edit Flyway history or assume application rollback can reverse schema. |
| Full database loss or storage corruption | Provision a fresh compatible target and use provider physical/PITR recovery or the logical procedure; never trust/copy live corrupt data files as the only recovery source. |
| Ownership-transfer mistake or role misconfiguration | Stop writers, restore known-good ownership/roles/grants from provisioning and verified backup evidence, and rerun ownership/V45 checks. Changing credentials alone does not repair ownership. |
| Regional/provider failure | Deployment-owned geographic recovery, DNS/routing, secrets, and WAL/backup replication must meet the selected objectives; the repository supplies no cross-region facility. |
| Backup corruption | Quarantine it, alert, use another independently verified recovery point, and investigate the integrity chain. Listing or checksumming alone does not replace restoration. |
| Application rollback after restore | Run only an application version compatible with the restored Flyway version. Database migrations are forward-only; do not downgrade or rewrite history. |
| Stripe newer than PostgreSQL | Keep payment traffic stopped while provider objects/events are compared with restored orders, payments, and webhook IDs. Existing provider/webhook identifiers support idempotent handling, but the repository has no general automated reconciliation system; missing post-snapshot events may need controlled replay/provider review. Never recreate a charge merely because its local row is absent. |

## Sensitive-data and audit limitations

Backups contain credentials-derived identifiers, personal data, order/payment metadata, and operational history. Never commit dumps to Git, place them in ordinary CI artifacts, use production data in CI/rehearsals, or expose paths, URLs, passwords, or contents in logs. The deployment owns encryption in transit/at rest, keys, access/audit policy, retention, legal deletion, secure destruction, and break-glass access because the repository provides no storage mechanism.

Backups protect the ADMIN histories from loss only to the chosen recovery point. V45 makes them append-only for the runtime identity; it does not make them cryptographically immutable or protect them from the table owner/superuser. Backup, restore, PITR, ownership changes, and privileged verification are administrative security events outside that runtime guarantee and require independent operational audit evidence.

## Production readiness record

Production is not restore-ready until the deployment owner can present: selected numeric RPO/RTO and measurements; responsible owner/escalation; backup and WAL/PITR configuration; encryption/key and access evidence; retention/deletion/geographic policy; successful integrity monitoring; compatible target/image/extensions/dictionaries; separately recoverable role and secret provisioning; a dated isolated rehearsal satisfying every invariant; measured restore and reconciliation duration; and documented Stripe/external-state handling.
