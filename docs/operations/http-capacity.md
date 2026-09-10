# HTTP admission capacity and backpressure

## Runtime model and audited defaults

The resolved runtime is Java 21, Spring Boot 4.1.1, Spring MVC, and embedded Tomcat 11.0.25 using its NIO HTTP/1.1 connector. Controllers return ordinary values or `ResponseEntity` values. Production code has no MVC `Callable`, `DeferredResult`, `WebAsyncTask`, `SseEmitter`, `StreamingResponseBody`, `@Async`, virtual-thread setting, servlet executor, or HTTP `Executor`/`TaskExecutor` override. Each admitted request therefore occupies a Tomcat worker while controller, security, database, BCrypt, and provider work executes. Scheduled workers have their separate Spring scheduler and are not an HTTP offload path.

Before the production profile overrides below, the effective Spring Boot/Tomcat policy is:

| Control | Tomcat 11.0.25 default | Spring Boot 4.1.1 default applied to embedded Tomcat | Repository production policy |
| --- | --- | --- | --- |
| Worker maximum | 200 | 200 (`server.tomcat.threads.max`) | Mandatory `SERVER_TOMCAT_THREADS_MAX` |
| Minimum spare workers | 10 | 10 (`server.tomcat.threads.min-spare`) | Inherited; this is an eager/spare floor, not an admission ceiling |
| Simultaneous connections | 8192 for NIO (`maxConnections`) | 8192 (`server.tomcat.max-connections`) | Mandatory `SERVER_TOMCAT_MAX_CONNECTIONS` |
| OS accept backlog | 100 (`acceptCount`) | 100 (`server.tomcat.accept-count`) | Mandatory `SERVER_TOMCAT_ACCEPT_COUNT` |
| Initial/request-line read timeout | 60 seconds (`connectionTimeout`) | No value is applied; Tomcat therefore retains 60 seconds (`server.tomcat.connection-timeout`) | Mandatory `SERVER_TOMCAT_CONNECTION_TIMEOUT` |
| Keep-alive idle timeout | Falls back to `connectionTimeout` when unset | No separate override; consequently follows the configured connection timeout | Inherited fallback |
| Requests per keep-alive connection | 100 (`maxKeepAliveRequests`) | 100 (`server.tomcat.max-keep-alive-requests`) | Inherited |

No custom connector, protocol handler, Tomcat executor, maximum keep-alive connection override, or maximum request connection override exists. The four mandatory values use Spring Boot's native `server.tomcat.*` binding rather than a duplicate application model. Missing placeholders or values that cannot bind/start Tomcat fail production startup. Numeric values are deliberately absent: worker, connection, backlog, and read-timeout budgets depend on per-replica CPU and memory, Hikari capacity, replica and edge topology, traffic mix, and latency/drain budgets. The deployment must validate positive, Tomcat-supported values as part of its configuration release.

With Tomcat's default `disableUploadTimeout=true`, `connection-timeout` also applies to socket reads while Spring/Jackson consumes a request body. It is a per-read inactivity timeout, not an absolute upload or whole-request deadline: a client that keeps supplying bytes can retain the connection and parsing worker longer. The otherwise-default 300-second `connectionUploadTimeout` is inactive. The keep-alive timeout defaults to `connection-timeout` while a persistent connection waits for another request. After body conversion/controller execution progresses without a blocked socket read, this listener setting does not time out checkout, a database transaction, a lock wait, BCrypt, or Stripe. See [Inbound HTTP request resource bounds](./http-request-bounds.md) for the complete audit.

## Ownership decision

This is **Outcome B: deployment-owned numbers, repository-required configuration**. The repository owns fail-closed presence and the property names; each deployment owns the values. Framework defaults are documented for audit purposes but are not accepted silently as production policy. The deployment edge continues to own TLS, authoritative client identity, coarse abuse/rate limiting, routing, and any queue before the application. Tomcat owns per-replica admission/execution capacity. Capacity rejection is not API rate limiting and this change adds no application `429` contract.

A production release must choose the four values together with its Hikari maximum and acquisition timeout, database and provider bounds, allocated CPU/memory, replica count, edge connection reuse and queueing, expected workload mix, and shutdown deadline. Do not equate worker threads with database connections: connections can be idle/keep-alive without a worker, and requests may use no database connection, borrow one briefly, perform CPU work, or wait on Stripe outside a database transaction.

## Request queueing model

A request may encounter these distinct layers, in order:

1. a deployment load balancer/proxy may queue or reject before routing;
2. the kernel performs TCP listener admission and handshake handling;
3. Tomcat tracks established connections up to `max-connections`, including keep-alive connections that are not executing;
4. after that capacity is reached, connections may wait in the OS accept backlog up to `accept-count`;
5. a parsed request waits for a Tomcat worker when all `threads.max` workers are occupied;
6. DB work may wait inside Hikari for a connection, bounded by its `connection-timeout`;
7. SQL may wait for a PostgreSQL lock, bounded by the applicable repository/deployment database policy; and
8. payment workflows may wait on Stripe transport attempts and retry backoff, bounded by the deployment-owned Stripe policy.

These layers are not interchangeable buffers. Large stacked queues retain sockets, request state, platform memory, servlet threads, and sometimes transactions or connections; add scheduling overhead; and allow work to become stale while tail latency grows. A queue can absorb an intended short burst, so zero is not automatically correct. The deployment must instead make the aggregate waiting and execution path fit its latency and shutdown budgets and must coordinate edge shedding with the per-replica ceiling.

## Representative resource shapes

| Workflow | Shape | Capacity notes |
| --- | --- | --- |
| Public product/category reads | DB-bound | Security/parsing plus read queries; connection is borrowed for database work, not necessarily the entire request. |
| Login | Mixed DB/CPU | User lookup followed by deliberately expensive BCrypt verification for an existing account, then JWT work on success. |
| Registration | Mixed CPU/DB | BCrypt hashing precedes role lookup/write; even a duplicate can consume hashing before the uniqueness conflict. |
| Cart mutations | DB-bound/mixed | Authenticated transactional reads and writes, with contention possible on cart/product state. |
| Checkout | Mixed DB/provider I/O | Transactional validation/reservation and payment workflow; Stripe waits can occupy the servlet worker without implying a held DB connection throughout. |
| Payment-intent initialization | Provider-I/O-bound/mixed | DB state/idempotency coordination plus bounded Stripe network work. |
| Stripe webhook | DB-bound/mixed | Signature/JSON CPU work plus idempotent registration and order/payment state transitions; no separate webhook executor. |
| Authenticated order reads | DB-bound | Authorization/current-user resolution and order queries. |
| ADMIN queries and commands | DB-bound/mixed | Protected queries and transactional recovery/state commands; some recovery paths coordinate durable provider work. |
| Actuator liveness | Lightweight CPU | Does not acquire PostgreSQL. |
| Actuator readiness/aggregate health | DB-bound | The standard `db` contributor borrows from the same Hikari pool as business traffic. |

BCrypt work occurs on Tomcat workers. Raising the worker ceiling without CPU evidence can admit more concurrent hashes than the CPU can progress, increasing runnable threads and latency for unrelated requests. Edge authentication throttling remains necessary because a thread ceiling is capacity isolation, not an abuse policy; BCrypt strength and authentication/registration responses remain unchanged.

## Hikari interaction and overload semantics

When Tomcat workers greatly outnumber available Hikari connections, as many DB-seeking request threads as the worker ceiling permits can block in Hikari (less workers doing non-DB work), each until the configured acquisition timeout. Their stacks and request state consume memory and the runnable/wakeup population adds scheduling cost. A timed-out acquisition becomes a request failure through the existing error handling; it is neither queue rejection nor `429`.

The readiness `db` contributor uses the same pool. Under saturation its health probe can also time out, report readiness down, and cause the edge to remove the replica while liveness stays up. This may help drain demand, but simultaneous removal of saturated replicas can shift traffic to peers and create positive feedback. No health membership changes are justified: deployments must combine readiness with pool and Tomcat metrics, configure routing thresholds/hysteresis appropriate to their platform, and leave enough probe and drain budget when selecting capacities.

When all workers are busy, an already admitted request waits for a worker while connections remain within connector capacity. At `max-connections`, further connections wait in the accept backlog. When that backlog is full, the OS/connector may refuse, reset, or leave connection attempts to time out; there is no guaranteed application response because no servlet necessarily runs. None of these conditions produces a repository-defined `429`. HTTP handler exceptions and status codes count as request outcomes; pre-servlet connection failures require edge, host, and connector telemetry.

## Shutdown and drain

Graceful shutdown marks readiness as refusing traffic and pauses new Tomcat request admission, but the external edge must observe that state and stop routing. Requests already accepted or executing consume the existing 30-second web lifecycle phase. Connector/OS queued connections are not extra guaranteed work that extends that phase. Oversized connection and accept queues can therefore leave more clients attached to a draining replica and make practical drain less predictable even though the lifecycle timeout still bounds the phase. The existing timeout and 65-second Compose allowance remain unchanged; production must add edge deregistration time before its force-kill deadline.

## Standard metrics

With Actuator and Micrometer, the exact stack registers standard Tomcat meters when the connector is running, including `tomcat.threads.busy`, `tomcat.threads.current`, `tomcat.threads.config.max`, `tomcat.connections.current`, `tomcat.connections.keepalive.current`, and `tomcat.connections.config.max`. Standard request instrumentation publishes `http.server.requests` with count and duration, and Tomcat's global binder publishes connector traffic/error/request meters under `tomcat.global.*`.

There is no standard application counter that fully represents an OS accept-backlog overflow or promises a particular rejected-connection response. Correlate edge connection/rejection signals and host TCP backlog evidence with Tomcat connection/thread meters, `http.server.requests`, Hikari pending/acquisition/timeout meters, readiness, PostgreSQL lock telemetry, and Stripe observations. Keep framework-defined URI/method/status tags bounded; never add raw paths, user/email/IP/request identifiers, or provider identifiers. Thresholds and alerts remain deployment-owned.
