# Database operations

## Local Docker PostgreSQL

| Setting | Default |
| --- | --- |
| Container service | `postgres` |
| PostgreSQL version | `18` |
| Database | `enterprise_shop_dev` |
| Host binding | `127.0.0.1:${POSTGRES_HOST_PORT:-5433}:5432` |
| Admin user | `${POSTGRES_USER:-postgres}` |
| Runtime application user | `${APP_DB_USER:-shop_dev}` |
| Volume | `enterprise_shop_postgres18_volume` |

The host may separately run a system PostgreSQL instance on `localhost:5432`; Docker PostgreSQL intentionally uses port `5433` by default. The custom PostgreSQL image preserves the Polish full-text-search dictionary files required by Flyway migration V5.

The `dev` profile keeps Hibernate in `ddl-auto: validate`. Schema changes must come from Flyway, not Hibernate auto-DDL. Local Flyway uses the admin/bootstrap identity by default through `spring.flyway.url`, `spring.flyway.user`, and `spring.flyway.password`; the application datasource uses the least-privilege runtime identity.

## Production connection capacity

Production Hikari capacity is a deployment-owned contract. Every replica has one application Hikari pool, and production
must supply `DATABASE_MAXIMUM_POOL_SIZE`, `DATABASE_MINIMUM_IDLE`, and
`DATABASE_CONNECTION_TIMEOUT_MILLISECONDS`. The repository deliberately provides no production numeric fallback.
Values must satisfy `maximumPoolSize > 0`, `minimumIdle >= 0`, `minimumIdle <= maximumPoolSize`, and HikariCP's
250-millisecond minimum connection timeout; invalid values stop startup and identify only the property.

Budget application connections database-wide rather than sizing one replica in isolation:

```text
simultaneously running replicas × DATABASE_MAXIMUM_POOL_SIZE
```

That product is only the application-pool portion of the budget. Reserve capacity for PostgreSQL administration and
operational tooling, Flyway's unpooled lifecycle connection, database/platform requirements, and failures that require
replacement connections. Count old and new replicas simultaneously during a rolling deployment. PostgreSQL
`max_connections` is not wholly available to application pools. The deployment must derive its values from its database
or proxy allowance, concurrency and transaction-duration evidence, rollout policy, and operational reserve; this
repository owns none of those numbers.

`DATABASE_MINIMUM_IDLE` is also per replica. A warm floor therefore consumes idle server connections as replicas and
rollout overlap increase. HikariCP 7.0.2 uses `minimumIdle = maximumPoolSize` when minimum idle is omitted, producing a
fixed-size pool, so production requires an explicit choice rather than accidentally inheriting that behavior. Zero is
valid when the deployment chooses elastic creation; a positive warm floor requires deployment evidence.

Connection acquisition waits at most `DATABASE_CONNECTION_TIMEOUT_MILLISECONDS`. At expiry Hikari throws a transient
SQL connection exception and records a timeout metric; HTTP work, authentication lookups, scheduled workers, and the
standard database health contributor all share this bound because they use the application pool. Choose it from the
request, worker, and shutdown budgets. It is not a transaction timeout and must not be copied from SMTP/Stripe bounds
or notification/reservation claim leases: those bound different operations and a complete workflow may acquire more
than one connection in separate transactions.

HikariCP 7.0.2 continues to own the unconfigured lifecycle defaults: 5-second validation timeout, 10-minute idle
timeout, 30-minute maximum lifetime, 2-minute keepalive, and 1-millisecond initialization-failure timeout. The
deployment must override relevant standard `spring.datasource.hikari.*` properties when a PostgreSQL service, network,
or proxy imposes a shorter connection lifetime or different keepalive policy. In particular, introducing PgBouncer,
RDS Proxy, or another intermediary requires a compatibility review; the repository assumes none of them and does not
invent their timeout values. JDBC4 `Connection.isValid` is used when no connection test query is configured.

Logical database consumers do not create additional application pools. JPA repositories and transactions, HTTP and
ADMIN operations, the outbox worker, notification claim/finalization, reservation-expiration claim/finalization, and
readiness `db` checks all borrow from the single application Hikari pool. Because production supplies an explicit
Flyway URL and credentials, Spring Boot creates a separate unpooled `SimpleDriverDataSource` for migration lifecycle
connections. Local role bootstrap and ownership-transfer scripts run `psql` outside the application JVM. Integration
tests share their Testcontainers PostgreSQL server while each Spring test context creates its own test application pool;
the test profile caps those pools at four with zero minimum idle. Docker Compose runs one optional development app
service and separate PostgreSQL and one-shot role-bootstrap services; it does not define production topology.

## SQL, lock-wait, and transaction boundedness

The resolved runtime stack is Spring Boot 4.1.1, Hibernate ORM 7.4.5.Final, PostgreSQL JDBC 42.7.12, HikariCP 7.0.2,
Java 21, and the repository's `postgres:18-alpine` server image. The following timeout layers are independent:

| Layer | Current repository contract | What it does not bound |
| --- | --- | --- |
| Hikari connection acquisition | Production must provide `DATABASE_CONNECTION_TIMEOUT_MILLISECONDS`. Hikari applies it only while `DataSource.getConnection()` waits for a pool entry. | SQL, a lock wait, or a transaction after the connection has been borrowed. |
| JDBC/network | No PostgreSQL JDBC `socketTimeout`, `Statement.setQueryTimeout`, or `Connection.setNetworkTimeout` policy is configured. | With the driver defaults, application-side network and statement execution have no repository-owned deadline. |
| SQL statement | No global JPA/Hibernate query timeout and no PostgreSQL `statement_timeout` are configured. | A slow plan, database resource wait, or blocked statement can retain a borrowed connection without a repository-owned bound. |
| Lock wait | No general JPA/Hibernate lock timeout and no PostgreSQL `lock_timeout` are configured. | Ordinary row/advisory lock acquisition is unbounded by repository configuration. |
| Spring transaction | There is no `spring.transaction.default-timeout`, transaction-manager override, or `@Transactional(timeout=...)`. Read-only transactions differ only in the read-only hint, not duration. | Transaction completion and idle-in-transaction time. A Spring timeout, if later used, is not a forceful wall-clock cancellation guarantee for arbitrary blocked driver I/O. |
| PostgreSQL idle transaction | No `idle_in_transaction_session_timeout` or `idle_session_timeout` is configured. | A session that is idle while its transaction remains open is not terminated by repository-owned policy. |

PostgreSQL JDBC cancellation is best effort: a JDBC query timeout schedules a separate cancel request, while
`socketTimeout` closes a connection after a socket read receives no data. Those are different failure and recovery
mechanisms and neither is supplied here. PostgreSQL `statement_timeout` measures statement execution on the server;
`lock_timeout` applies while acquiring PostgreSQL locks (including advisory locks) and is useful only when shorter than
the applicable statement timeout. A server/session timeout cancels the statement and leaves an explicit transaction
failed until rollback; transaction-scoped advisory locks are released when that transaction ends, or when the session
ends. Merely terminating an HTTP client does not prove that the servlet thread or JDBC statement has been cancelled.
Normal transaction rollback releases locks; forced JVM/session termination causes PostgreSQL to release them when it
detects the disconnected session.

### Blocking-lock inventory

The production tree has the following explicit lock acquisition sites:

| Site | Purpose and contention class | Current wait behavior |
| --- | --- | --- |
| `OrderRepository.acquireCheckoutIdempotencyLock` | Transaction-scoped advisory lock serializes checkout by user and normalized idempotency key. | Blocking and potentially unbounded. The waiting request thread retains its Hikari connection. Replacing it with `pg_try_advisory_xact_lock` would turn serialization into an immediate failure/retry contract and is not correctness-equivalent. |
| `CartRepository.findByUserIdWithItemsForUpdate` | Cart mutations and post-payment reconciliation serialization. | Normal short contention, but no configured upper bound. |
| `CartRepository.lockCartCreationForUser` | Transaction-scoped advisory serialization only after a cart lookup finds no row. | Same-user first touches wait for the creator transaction; hash collisions can conservatively serialize unrelated first touches. |
| `ProductRepository.findByIdWithLock` | Checkout reservation, inventory restoration, product mutation, and review aggregate serialization. | Checkout/inventory serialization, potentially unbounded; checkout sorts product identifiers before acquiring multiple product locks to reduce deadlock risk. |
| `DiscountCodeRepository.findByCodeIgnoreCase` | Serializes the discount usage check/update. | Correctness-critical row serialization, potentially unbounded. |
| `OrderRepository.findByIdForUpdate` | Payment convergence and reservation-expiration state transitions. | Correctness-critical row serialization, potentially unbounded. |
| `PaymentRepository.findByOrderIdForUpdate` | Payment initialization and terminal webhook convergence. | Correctness-critical row serialization, potentially unbounded. |
| `NotificationRepository.findByIdForUpdate` | Delivery finalization/failure after SMTP returns. | Short worker finalization contention, potentially unbounded. SMTP is outside this transaction. |
| `ReservationExpirationWorkRepository.findByIdForUpdate` | Claim finalization, failure, recovery, and ADMIN operations. | Worker/admin contention, potentially unbounded. |
| `OutboxEventRepository.findByIdForManualRequeueUpdate` | ADMIN manual requeue. | Admin/manual-operation contention, potentially unbounded. |
| `OutboxEventRepository.findDuePendingByIdForUpdateSkipLocked` | Outbox claim/failure coordination. | `FOR UPDATE SKIP LOCKED` deliberately does not wait for a row already locked by another worker. |
| `ReservationExpirationWorkRepository.findClaimableForUpdate` | Reservation-expiration claim coordination. | `FOR UPDATE SKIP LOCKED` deliberately avoids row-lock waits. |
| `NotificationRepository` claim query | Notification delivery claim coordination. | `FOR UPDATE SKIP LOCKED` deliberately avoids row-lock waits. |
| `ProductCatalogFacadeImpl.restoreInventory` | Explicit JDBC `SELECT stock ... FOR UPDATE` before inventory restoration. | Inventory serialization, potentially unbounded. |

`StripeWebhookEventRepository.registerIfAbsent` is the only native modifying query outside those repositories. Its
`INSERT ... ON CONFLICT DO NOTHING` has no explicit lock clause, but PostgreSQL uniqueness/index conflict resolution can
still wait for a concurrent transaction. Ordinary ORM inserts, updates, deletes, foreign-key checks, and unique checks
can likewise wait even though no lock syntax appears in repository source. Consequently the explicit-lock list is not
a claim that all other SQL is non-blocking.

### Discount-code timeout finding

The former `jakarta.persistence.lock.timeout = 3000` hint was introduced with the discount repository and had no
accompanying test, design record, latency evidence, or API contract that established three seconds as a business
requirement. It was therefore dead, misleading configuration and has been removed.

In the resolved Hibernate/PostgreSQL stack, a positive millisecond lock hint is carried in Hibernate's lock options,
but PostgreSQL's dialect renders an ordinary `FOR UPDATE` for a positive value. It neither executes
`SET LOCAL lock_timeout` nor creates a client timer, and it is distinct from `jakarta.persistence.query.timeout` and
JDBC `Statement.setQueryTimeout`. The value therefore did **not** enforce a three-second PostgreSQL lock wait. Special
Hibernate lock modes such as no-wait and skip-locked can change SQL rendering, but that does not make an arbitrary
positive JPA duration enforceable on this dialect. The repository retains the pessimistic lock that serializes the
discount usage check/update, while the runtime deployment owns the generic lock-wait duration. When configured for the
runtime identity, PostgreSQL `lock_timeout` controls that wait on the server.

### Ownership decision

The audit selects **Outcome B** for generic statement, lock-wait, transaction, and idle-in-transaction bounds. Safe
values depend on measured query/lock latency, request and worker budgets, database performance, rollout/shutdown policy,
and operational recovery. The repository intentionally does not invent universal numeric defaults. The checkout
advisory lock and each row lock remain repository-owned correctness mechanisms, but their generic wait durations do
not become application correctness numbers. Existing `SKIP LOCKED` coordination remains non-blocking and needs no lock
timeout hint.

Prefer PostgreSQL role or database defaults scoped to the least-privilege runtime identity when a deployment adopts
`statement_timeout`, `lock_timeout`, and `idle_in_transaction_session_timeout`. This covers ORM and native SQL without
high-cardinality per-query configuration and keeps the policy visible to database operators. Validate the effective
values on a fresh runtime session during deployment. JDBC URL `options` or Hikari connection-init SQL can also scope a
runtime-session policy, but they couple operational policy to application configuration and should not be combined
with role defaults without a clear precedence rule. Per-query hints are reserved for a proven, query-specific contract.

Do not apply runtime timeouts to `FLYWAY_USER`, the Flyway URL, the PostgreSQL administrative identity, role bootstrap,
ownership transfer, backup/restore, or reviewed maintenance sessions. Migrations and privileged operations have
different duration and recovery requirements. Runtime/Flyway identity separation is therefore also the timeout-policy
boundary; database-wide defaults are unsafe unless they explicitly exclude those identities.

### Failure and observability contract

PostgreSQL reports statement-timeout cancellation as SQLSTATE `57014` (`query_canceled`) and lock-timeout cancellation
as SQLSTATE `55P03` (`lock_not_available`). Terminating an idle-in-transaction session reports SQLSTATE `25P03` and is
a connection-ending failure rather than a normal application result. Depending on the path,
the JDBC error is converted through Hibernate/JPA and Spring persistence exception translation. The current global
handler has no timeout-specific mapping, so an uncaught database timeout follows the sanitized unexpected-error 500
contract; it is not the existing optimistic-lock 409. No 409 or 503 contract is inferred merely by enabling an
operational timeout. Logs and responses must continue to omit raw SQL, lock keys, database identities, connection
strings, and driver messages.

Standard Hikari/Micrometer signals already distinguish acquisition pressure (`hikaricp.connections.pending`,
`hikaricp.connections.acquire`, and `hikaricp.connections.timeout`) from long borrowed usage
(`hikaricp.connections.usage`). They cannot identify whether usage time was query execution, a row/advisory lock wait,
application work inside a transaction, or commit/rollback. PostgreSQL `pg_stat_activity`, `wait_event_type`,
`wait_event`, transaction age, database logs, and timeout SQLSTATEs provide that database-side distinction. Spring does
not currently publish a dedicated query-, lock-, or transaction-timeout counter. No custom SQL metric or
high-cardinality tag is justified until a concrete repository-owned timeout contract exists, and this audit defines no
alert threshold.

## Production migration identity

Production requires two distinct PostgreSQL login identities. `DATABASE_USERNAME` and `DATABASE_PASSWORD` configure the least-privilege runtime datasource. `FLYWAY_USER` and `FLYWAY_PASSWORD` configure the schema migration identity; `FLYWAY_URL` may select a dedicated migration endpoint and otherwise uses `DATABASE_URL`. None of the production Flyway credentials default to the runtime credentials. Missing migration credentials therefore stop startup rather than silently running DDL through the application identity.

The migration identity applies Flyway before Hibernate validates the schema and owns the objects it creates. The runtime identity receives only `CONNECT`, schema `USAGE`, application DML, required sequence access, and required function execution. It must not be a superuser, database or schema owner, table owner, or hold `CREATEDB`/`CREATEROLE`. Production provisioning must establish default privileges for objects created by `FLYWAY_USER`, grant the runtime privileges, and then start the application with both credential sets available through secret management.

This ownership split is part of the V45 append-only boundary. The mutation-rejection trigger deliberately permits the table owner to perform maintenance, while rejecting `UPDATE` and `DELETE` from the non-owner runtime session with SQLSTATE `42501`. Running Flyway as `DATABASE_USERNAME` would make a fresh deployment's runtime identity the protected tables' owner and defeat that boundary.

Production uses `baseline-on-migrate: false`. Supported fresh deployments migrate an empty database from V1, and upgrades retain their existing `flyway_schema_history`; neither path needs automatic baselining. A non-empty schema without Flyway history is rejected so an accidental connection cannot silently adopt an unmanaged schema. Any exceptional legacy adoption requires a reviewed, explicit one-time Flyway baseline procedure before normal startup. Development retains its existing automatic-baseline compatibility.

### Existing production database rollout

Databases first migrated before dedicated Flyway credentials were required may still be owned by the runtime identity. Merely configuring a new `FLYWAY_USER` does not change existing PostgreSQL ownership. Production startup therefore checks, after Flyway and Hibernate initialization, that the runtime connection does not own any of the three V45-protected ADMIN action-log tables and fails if legacy ownership remains.

Use `scripts/transfer-prod-db-ownership.sh` only as a reviewed maintenance operation for that legacy state. It requires a separate PostgreSQL superuser administrative connection through `DATABASE_ADMIN_URL`/`DATABASE_ADMIN_USER`, the exact expected `DATABASE_NAME`, `LEGACY_RUNTIME_USER`, `FLYWAY_USER`, and optional `APP_SCHEMA`. The script validates identifiers and the connected database, refuses equal roles, and refuses `REASSIGN OWNED` when the legacy role owns another database, a tablespace, or objects outside the selected application schema. Within those safeguards, reassignment covers the current database's schema-managed tables, indexes, sequences, functions, text-search objects, extensions, schema, and database ownership; the script then restores current and default runtime DML, sequence, and function grants. It never reads or prints a database password; supply the administrative password through the normal `PGPASSWORD`/password-file mechanism.

For an existing database, use this ordering:

1. Create and verify a recoverable backup under the [disaster-recovery contract](./disaster-recovery.md), then provision the distinct migration identity.
2. Audit the legacy runtime role's ownership and review the transfer script inputs against the intended database and schema.
3. Stop application writers, run the privileged ownership transfer, and independently verify owners and runtime grants.
4. Configure `DATABASE_*` for runtime and `FLYWAY_*` for migration through secret management.
5. Start the application. Flyway validates the existing history and applies only pending migrations, Hibernate validates the schema, and the production ownership invariant rejects any remaining protected-table ownership.
6. Verify application health, normal runtime DML, and V45 INSERT/SELECT versus UPDATE/DELETE behavior before restoring traffic.

Do not grant the runtime role membership in the migration or administrative role. Do not use automatic baselining or edit `flyway_schema_history` as an ownership-transfer mechanism.

## Least-privilege runtime grants

The runtime role has `LOGIN`, `CONNECT` on `enterprise_shop_dev`, `USAGE` on schema `public`, DML privileges on existing application tables, sequence privileges needed by generated IDs, and function execution where required. It is explicitly kept as `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION` and does not own the PostgreSQL server.

The operator-history tables (`notification_admin_action_logs`, `outbox_event_admin_action_logs`,
`reservation_expiration_admin_action_logs`, and `stripe_payment_conflict_dispositions`) are append-only for non-owner
runtime identities. The runtime role can insert and query their rows, but database triggers reject row updates and
deletes even though the general table grants include those operations. Flyway creates the triggers while connected as
the administrative schema owner, after the one-shot role bootstrap and before the runtime datasource begins normal
work. Re-running the broad, idempotent role bootstrap therefore cannot remove the protection.

This boundary protects against accidental application or repository mutation and direct mutation through runtime credentials. It does not protect against a PostgreSQL superuser, the table owner, or a migration administrator deliberately changing or disabling the triggers. Administrative retention, if introduced in the future, must use a separate privileged identity; no audit-log retention process currently exists. The [operational data-retention contract](./data-retention.md) defines the evidence and execution boundary required before such maintenance can be introduced. A future operator-history table must attach `reject_runtime_admin_action_log_mutation()` in the same forward Flyway migration that creates the table.

Run the idempotent bootstrap whenever local role grants need refreshing. PostgreSQL is long-running and uses `up --wait`; the bootstrap is a one-shot administrative task and uses `run`, where exit code 0 is success and `--rm` removes the temporary one-off container:

```bash
docker compose up -d --wait postgres
docker compose run --rm --no-deps --build database-role-bootstrap
```

For the full stack:

```bash
docker compose --profile full up -d --build --wait
```

## Inspection commands

```bash
docker compose exec -T postgres pg_isready -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-enterprise_shop_dev}"
docker compose exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-enterprise_shop_dev}" \
  -c "SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolreplication FROM pg_roles WHERE rolname = 'shop_dev';"
docker volume inspect enterprise_shop_postgres18_volume
```

Do not run `docker compose down -v` unless you intentionally want to delete local database data.

## Test database independence

Persistence integration tests use independent Testcontainers PostgreSQL containers and do not use the Docker Compose database or its named volume. Testcontainers may use its dynamically created database owner for isolated Flyway migrations.

## Schema ownership

| Area | Tables/features |
| --- | --- |
| User/security | Users, roles, user-role join, case-insensitive email uniqueness. |
| Catalog | Categories, products, product images, product search/rating support. |
| Reviews | Product reviews and related constraints. |
| Cart | Carts and cart items. |
| Orders/payments | Orders, order items, discount codes, payments, Stripe webhook idempotency events. |
| Outbox/notifications | `outbox_events` and `notifications`. |

## Persistence conventions

- New schema changes require a new Flyway migration.
- Do not edit historical migrations unless explicitly directed for a controlled repair.
- Keep JPA mappings and migrations aligned; `ddl-auto: validate` should continue to pass.
- Preserve database constraints for uniqueness, status values, non-negative amounts/stock, and relationship integrity.
- Payment/order/cart/stock changes are consistency-sensitive and require focused tests.

## Timestamp note

Historical migrations use plain `TIMESTAMP` columns in several tables. Newer outbox and notification migrations use `TIMESTAMP WITH TIME ZONE` for `created_at`, processing/sent timestamps, and related event state. This documentation records the current real state; do not rewrite existing migrations in documentation-only work.
