# Stripe network boundary and recovery

## Audited SDK behavior

The repository pins Stripe Java `33.3.0`. In that version the legacy static/global configuration defaults to a 30-second connect timeout, an 80-second read timeout, and two network retries. A newly built `StripeClient` has the same timeout defaults but zero retries unless configured. The SDK's default transport is `HttpURLConnection`; it creates a connection for each request and does not own an application-visible connection pool. JVM `HttpURLConnection` keep-alive behavior may reuse underlying connections, but the repository neither configures nor depends on a Stripe pool.

The SDK retry wrapper is operation-agnostic, so its configured retry count applies equally to PaymentIntent create, retrieve, and cancel. It retries connection failures whose cause is `ConnectException` or `SocketTimeoutException`, responses explicitly marked `Stripe-Should-Retry: true`, HTTP 409, and HTTP 5xx; `Stripe-Should-Retry: false` takes precedence. Backoff is exponential with jitter, with a 500 ms minimum and 5 second maximum. Each retry sends the same constructed request, including its idempotency header.

These facts are tied to the pinned SDK source: [global defaults](https://github.com/stripe/stripe-java/blob/v33.3.0/src/main/java/com/stripe/Stripe.java), [`StripeClient` defaults and configuration](https://github.com/stripe/stripe-java/blob/v33.3.0/src/main/java/com/stripe/StripeClient.java), [retry decisions and backoff](https://github.com/stripe/stripe-java/blob/v33.3.0/src/main/java/com/stripe/net/HttpClient.java), and [`HttpURLConnection` timeout application](https://github.com/stripe/stripe-java/blob/v33.3.0/src/main/java/com/stripe/net/HttpURLConnectionClient.java). An SDK upgrade must re-audit those contracts.

## Ownership model: deployment-owned values

Production must supply `STRIPE_CONNECT_TIMEOUT`, `STRIPE_READ_TIMEOUT`, and `STRIPE_MAX_NETWORK_RETRIES`. Connect and read values use Spring `Duration` syntax, must be positive, and must fit the SDK's positive millisecond integer API; retries must be zero or positive. Missing or invalid production values fail startup. The `dev` profile explicitly mirrors the audited legacy defaults (`PT30S`, `PT80S`, and `2`) only for local convenience, while tests use short deterministic values. Neither profile establishes a production latency policy.

Concrete production values cannot honestly be selected from source alone: they depend on observed Stripe latency, the inbound request timeout, provider tolerance, platform deregistration latency, and the deployment's unconditional-kill deadline. Operators must include all configured attempts and SDK backoff in the conservative maximum, keep reservation calls below the five-minute claim lease, and decide deliberately whether that maximum fits within the 30-second Spring phase. A connect or read timeout bounds the corresponding socket operation, not the complete business operation or a guaranteed provider response time.

All outbound calls use one injected `StripeClient`, which owns credentials and network policy without relying on mutable `Stripe.apiKey` or other global client settings. Per-operation `RequestOptions` remain responsible only for operation identity. Webhook signature verification remains local and uses no outbound client.

## Operation semantics

### Create

PaymentIntent creation uses `order-payment-intent-{orderId}`. Checkout preparation and local payment attachment run in separate transactions around, not across, provider I/O. A caller retry for the same order reconstructs the same key, and every SDK retry reuses it. If Stripe commits but the response is lost or times out, no local provider ID may be attached; the later checkout or reservation initialization repeats the keyed create, obtains the same provider operation result, and attaches it. The read timeout therefore creates an ambiguous observation, not permission to create with a different key.

### Retrieve

Retrieval is a read and is inherently safe to repeat. Reservation expiration uses it after committing its durable claim and outside a database transaction. Retries can improve tolerance of transient failures but multiply worker occupancy by attempt timeouts and backoff, so the deployment must include the full policy in its claim and shutdown analysis.

An expired lease permits overlapping provider observations: a replacement owner can
retrieve while the original owner is still blocked or completing. The read itself is
harmless to duplicate. Token checks prevent the stale owner from finalizing over the
replacement claim, but the worker is at-least-once rather than exactly-once.

### Cancel

Abandoned reservation cancellation uses `order-reservation-expiration-{orderId}`. SDK retries preserve that key. If cancellation succeeds at Stripe but its response is lost, the claim is retried; a later retrieve observes `canceled` and converges local payment, order, and inventory state. A signed `payment_intent.canceled` webhook converges through the same terminal transition. Repeating cancel with the same key cannot authorize a distinct cancellation operation.

Two owners can therefore repeat the same keyed cancellation after lease expiry without
authorizing a second logical cancellation. Payment creation uses its separate stable
order key, and terminal payment/order transitions converge under database locks.
Inventory restoration occurs only through that convergent canceled transition, so an
overlap does not release inventory twice even though provider calls can overlap.

### Webhooks and health

Webhook construction verifies the supplied signature locally before durable event-ID registration. Exact replay protection and succeeded, failed, or canceled terminal convergence are unchanged; transport timeouts do not apply to this boundary. Stripe remains excluded from readiness and liveness because replica admission and restart must not depend on an external provider. Durable request idempotency, webhook replay protection, claims, and terminal reconciliation—not a health probe—recover ambiguous outcomes and forced termination.
