# Production edge and public-authentication abuse protection

## Repository-owned boundary

This repository builds a Spring Boot application image, but it does not define a production deployment. It contains no
production ingress, reverse proxy, load balancer, CDN, WAF, certificate, listener, replica, or service-network manifest.
The Compose stack is a development facility: its application uses the `dev` profile and publishes port 8080 only on
`127.0.0.1`. That topology is not evidence of how production traffic reaches the application.

The production contract is therefore:

- the application must not be exposed directly to the public internet;
- a deployment-owned edge must terminate TLS, enforce coarse request-abuse controls, and be the only network peer
  permitted to reach the application listener;
- the application-to-edge hop and any TLS used on that hop are deployment responsibilities because this repository
  does not configure an application certificate or HTTPS listener;
- production may use one or many application replicas; no correctness or security decision may assume a single JVM;
- PostgreSQL is shared application state, but there is no shared rate-limit store and it must not be repurposed as one
  without a separately reviewed availability and capacity design.

`application-prod.yml` explicitly sets `server.forward-headers-strategy: none`. Consequently, attacker-selected
`Forwarded`, `X-Forwarded-For`, and `X-Real-IP` values are ordinary untrusted headers and are not used by Spring Boot to
rewrite the request's peer address, scheme, host, or port. An internet caller can send those header names whenever an
HTTP path permits it, including through a proxy that fails to sanitize them; neither the application nor an
application limiter may treat their values as authoritative. Direct access to the application listener does not
elevate them to trusted identity.

If a future deployment needs forwarded-header processing, it must first add a repository-owned or independently
verified deployment contract that proves all of the following:

1. the first trusted edge removes every inbound forwarding header and writes authoritative metadata from the accepted
   connection;
2. network policy permits the application listener to receive traffic only from the final trusted proxy hop;
3. every additional proxy has a documented order and trust list, replaces or appends metadata consistently, and
   rejects paths that bypass the chain;
4. source-address restoration selects the first address outside the trusted proxy chain rather than an
   attacker-prepended value; and
5. direct-listener and multi-proxy integration tests demonstrate the same rules.

Enabling framework forwarded-header support by itself is not an acceptable substitute for that boundary.

## Current authentication controls

`POST /api/v1/auth/login` and `POST /api/v1/auth/register` are intentionally public and execute in the application.
Credential correctness and request abuse are separate concerns. Existing credential controls normalize email with
trim plus locale-independent lowercase, bound email and name lengths, bound BCrypt input to 72 UTF-8 bytes, hash new
passwords with BCrypt, return equivalent generic 401 responses for expected login failures, preserve infrastructure
failures as 500 responses, and never issue a JWT on failure. Registration atomically relies on the case-insensitive
database uniqueness constraint and retains the product's explicit `409 USER_ALREADY_EXISTS` contract.

Those controls reduce disclosure and bound individual-request work, but they are not throttling. There is currently no
limit on login attempts, account creation, BCrypt operations, user lookups, or writes.

## Threat model

### Login

- **Single-source brute force:** repeated guesses consume a user lookup and, for an existing account, BCrypt CPU. The
  external edge must constrain requests per authoritative source before they consume application capacity.
- **Distributed credential stuffing against one account:** source-only limits can be bypassed with many origins. A
  future account-oriented policy would need the same normalized identifier used by authentication, a keyed
  pseudonymous representation rather than raw email, bounded expiry/cardinality, and shared atomic state across all
  replicas. The repository has no suitable store today.
- **Many-account attacks from one source:** an edge source policy is the appropriate first layer because it rejects the
  traffic before database and BCrypt work. Account-only controls would miss this shape.
- **Account-lockout denial of service:** durable lockout is intentionally not used. An attacker who knows an email must
  not be able to disable that account by deliberately submitting bad passwords. Any future account policy should
  delay or throttle attempts, not irreversibly change account state.
- **Cost and observability:** valid-format attempts cause database authentication work and existing-account attempts
  cause deliberately expensive BCrypt verification. Metrics or logs keyed by email, IP, request ID, token, password,
  or arbitrary exception text would create sensitive or attacker-controlled cardinality and are prohibited.

### Registration

Automated registrations cause a BCrypt hash, role lookup, transaction, and database write for each accepted unique
email. Bursts can therefore amplify CPU, connection-pool, WAL, index, and storage use. Duplicate normalized emails
still incur password hashing before the uniqueness violation is observed, so repeatedly targeting one address also
has material cost. Source throttling, bot management, and—when product requirements justify it—an edge challenge are
deployment-edge responsibilities.

The normalized-email uniqueness rule makes case and surrounding whitespace ineffective for creating duplicates. It
does not make email a safe metric dimension or an automatically safe limiter key: attackers can generate unbounded
unique identifiers. An account/identifier policy would require keyed pseudonymization, an entry cap, expiry, and a
defined response when capacity is exhausted.

The explicit duplicate-email 409 reveals that an account exists. It is retained because it is the current API and
frontend-facing product contract. A generic asynchronous or success-shaped registration response could reduce
enumeration, but would change response status and error semantics and would require coordinated product, frontend,
email-verification, OpenAPI, localization, and contract-test work. It is a separate future decision, not a rate-limit
side effect.

## Ownership decision

The current model is **external-edge-only request throttling**, with no application limiter. This is the smallest
correct model because the repository cannot authoritatively identify the original network client, does not rule out
multiple replicas, and owns no distributed limiter state. A per-JVM map, cache, or token bucket would be bypassable
across replicas and would turn attacker-chosen keys into a memory-exhaustion surface. Adding Redis solely for this
feature would invent an unowned operational dependency and failure mode.

The deployment owner must configure independent policies for login and registration at the authoritative edge. At a
minimum, use bounded bursts and sustained rates per edge-observed source, cap total endpoint traffic to protect BCrypt
and database capacity, and define behavior for IPv4/IPv6 aggregation, trusted proxy chains, and edge-state failure.
Exact values are deployment capacity and risk inputs and are deliberately not invented here. Distributed attacks
against a single account remain a known residual risk until shared application-independent identity throttling is
designed.

Edge enforcement should be partially degraded rather than silently absent or a permanent authentication outage:
retain conservative local edge protection when centralized policy/state is unavailable, shed traffic above a hard
capacity ceiling, and expose an operator-visible degraded signal. The exact behavior belongs to the selected edge
product and must be exercised by that deployment's failure tests.

No application `429 Too Many Requests`, `Retry-After`, limiter metric, or limiter state is introduced. The edge owns
its rejection contract and should return 429 with a meaningful `Retry-After` when it can calculate one, preserve or
generate the request-correlation header, and count decisions only with fixed low-cardinality dimensions such as
operation, outcome, and policy. It must never log submitted credentials or tag telemetry with an email or IP address.

## Production readiness gate

A production deployment is not ready for public authentication traffic until its owner supplies reviewable evidence
for TLS termination, application-listener isolation, forwarding-header sanitation, source extraction through every
proxy hop, separate login and registration policies, aggregate capacity protection, multi-replica behavior, limiter
state degradation, low-cardinality telemetry, and direct-bypass tests. That evidence is external to this repository's
current runtime and CI.
