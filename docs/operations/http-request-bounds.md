# Inbound HTTP request resource bounds

## Audited stack and ownership

This audit applies to Java 21, Spring Boot 4.1.1, Spring MVC, Jackson 3.1.5, and embedded Tomcat 11.0.25 with the NIO HTTP/1.1 connector. The selected model is mixed:

- **Outcome B (deployment-owned)** applies to a generic raw request-body ceiling. Neither the application nor Stripe provides evidence for one repository-wide byte value, and Tomcat has no connector attribute that limits every JSON or raw body. Every production edge must reject bodies above a reviewed deployment limit before forwarding them. Select the value from measured traffic, client compatibility, replica memory, and edge behavior; this repository deliberately does not invent it.
- **Outcome C (finite framework defaults)** applies to headers, header count, cookies, parameters, form bodies, multipart metadata, and abandoned-body swallowing. The exact defaults are recorded below. There is no current evidence for replacing them.
- DTO validation remains an **application semantic contract**, not a transport bound. It runs after framing and body conversion and cannot protect the parser from an arbitrarily large raw body.

No application behavior or OpenAPI schema changes result. There is no new application `413 Payload Too Large` contract and no custom body-size filter. The deployment edge's body rejection is an operations contract and must be tested there.

## Request parsing paths

All typed bodies in the production tree are JSON. Spring MVC's Jackson converter reads the servlet input stream and constructs the target DTO directly; it does not first buffer the complete body as a byte array. This is not a memory/work bound: JSON tokens, strings, arrays, object state, and ignored input can be allocated or scanned before Bean Validation runs.

The Stripe endpoint uses Spring's string converter. It consumes the entire body into a Java `String` before the controller calls signature verification. The exact received string is passed to Stripe without parse/re-serialization. No Tomcat or Spring MVC raw-body byte ceiling protects that conversion.

There is no production `@RequestPart`, `MultipartFile`, servlet input-stream/reader access, or form-urlencoded body handler. Tomcat parses form bodies into parameters only when parameter access triggers it. Standard servlet multipart support is on the classpath, but no route consumes parts. A future form/upload endpoint must repeat this audit.

## Body-bearing endpoint inventory

Unless a controller declares another media type, these `@RequestBody` methods use Spring MVC content negotiation; the supported application contract and tests use `application/json`. Every authenticated request may cause JWT parsing and an active-user DB lookup in the security filter before controller invocation.

| Method and path | Representation and semantic limits | Access | Work after conversion |
| --- | --- | --- | --- |
| `POST /api/v1/auth/login` | `LoginRequestDTO`: email required/email/max 255 characters; password required/max 72 UTF-8 bytes | Public | User lookup, BCrypt verification for an existing account, JWT creation on success |
| `POST /api/v1/auth/register` | `RegisterRequestDTO`: email max 255; password 8–72 characters and max 72 UTF-8 bytes; repeated password required/max 72 UTF-8 bytes; first/last name max 100 each | Public | BCrypt hash, role/user reads and registration write |
| `POST /api/v1/me/cart/items` | `AddToCartRequestDTO`: product UUID required; quantity at least 1 | Authenticated | Cart/product reads and transactional writes |
| `PATCH /api/v1/me/cart/items/{productId}` | `UpdateCartItemRequestDTO`: quantity required and at least 1 | Authenticated | Cart/product reads and transactional writes |
| `POST /api/v1/me/orders/checkout` | `OrderCheckoutRequestDTO`: discount code 3–20 when present; notes max 500; `Idempotency-Key` required/nonblank/max 128 characters | Authenticated | Cart/order/inventory DB work and bounded Stripe provider work |
| `POST /api/v1/webhooks/stripe` | Raw `String`; no semantic or transport length constraint | Public, authenticated by Stripe signature | Signature verification, provider-event construction, idempotency and order/payment DB work |
| `POST /api/v1/admin/products`; `PUT /api/v1/admin/products/{id}` | `ProductCreateDTO`: name max 255, SKU max 50, description max 5,000; price/stock/UUID constraints; each non-null image URL is max 512 Java UTF-16 code units; image URL list count remains product-policy unresolved | ADMIN | Category/product reads and writes |
| `POST /api/v1/admin/categories`; `PUT /api/v1/admin/categories/{id}` | `CategoryCreateDTO`: name max 150; description max 500; optional parent UUID | ADMIN | Category reads and writes |
| `PUT /api/v1/admin/users/{id}` | `UserUpdateDTO`: first/last name required/max 100 each | ADMIN | User read and write |
| `POST /api/v1/reviews` | `ProductReviewRequestDTO`: product UUID required; rating 1–5; comment max 1,000 | Authenticated | Product/purchase/review reads and write |

`OrderCreateRequestDTO` and `UserCreateDTO` exist but no production controller accepts them. Their missing collection/string maxima are not current inbound endpoint surface. The product-image item bound mirrors the existing `VARCHAR(512)` persistence width conservatively: PostgreSQL measures characters, while Bean Validation applies `CharSequence.length()` and therefore counts Java UTF-16 code units. The collection has no item-count constraint. Generic raw-body boundedness remains a separate concern that must occur before DTO construction.

## Effective Tomcat and servlet limits

The repository does not override these Tomcat 11.0.25 defaults:

| Surface | Effective value | Scope and rejection behavior |
| --- | --- | --- |
| Request line plus request headers | 8,192 bytes (`maxHttpRequestHeaderSize`) | Connector parsing; rejected before servlet/controller execution, commonly HTTP 400 or a closed connection if no response can be written |
| Header count | 100 (`maxHeaderCount`) | Connector-wide aggregate; no custom per-header limits |
| Cookie count | 200 (`maxCookieCount`) | Tomcat cookie parsing; stateless authentication does not use cookies |
| Request parameters | 1,000 (`maxParameterCount`) | Query plus parsed form/multipart parameters; excess parameters are ignored after the limit; not a JSON token limit |
| Form POST converted to parameters | 2 MiB (`maxPostSize`) | Form-urlencoded/parameter conversion only; **does not limit JSON or Stripe raw strings** |
| Multipart parts | 50 (`maxPartCount`) | Multipart only; no current endpoint requests parts |
| Header bytes per multipart part | 512 (`maxPartHeaderSize`) | Multipart only |
| Aborted body swallowed | 2 MiB (`maxSwallowSize`) | Bytes drained after an aborted upload; not an accepted-body ceiling |
| Spring servlet multipart file/request | 1 MiB / 10 MiB defaults | Standard multipart resolver only; neither limits JSON and no current endpoint consumes multipart |
| Generic JSON/raw body | **No Tomcat or Spring MVC maximum** | Must be bounded by the production edge under Outcome B |

`Authorization`, `Stripe-Signature`, `Idempotency-Key`, cookies, and forwarded headers share the 8 KiB aggregate buffer and 100-header count. This bounds aggregate header parsing before application code. Checkout separately bounds `Idempotency-Key` semantically. Forwarded headers remain untrusted. No per-header filter or logging of rejected values is justified.

## Slow clients and execution timing

Tomcat defaults `disableUploadTimeout=true`, and Spring Boot 4.1.1 does not change it. Thus mandatory production `server.tomcat.connection-timeout` applies while waiting for the request URI line and to socket reads while Spring/Jackson consumes a body. The otherwise-default 300-second `connectionUploadTimeout` is inactive unless `disableUploadTimeout=false`; this repository does not change it.

This is a socket-read inactivity timeout, not a total upload deadline: a sender delivering data before each read times out can retain an admitted connection and, during MVC conversion, a worker for longer. The edge must own an absolute upload duration or minimum-rate policy if needed. After conversion, this timeout does not bound controller, BCrypt, DB, or provider work.

## Stripe and authentication findings

Stripe requires verification against the unmodified payload. Tests establish preservation of that behavior, but Stripe documentation reviewed for this integration does not publish an authoritative maximum webhook payload supporting a repository-owned number. The webhook remains inside the deployment-owned body policy. Its valid payload is fully materialized before signature checking, so signature failure does not undo that memory/parsing cost.

Login and registration constraints prevent oversized accepted credentials from reaching BCrypt, but only after conversion. Large raw JSON can consume socket time, parsing, and memory first. Valid JWT-bearing requests can cause active-user lookup before MVC reads a body. Finite connector header limits bound headers; the deployment body ceiling must reject oversized public authentication bodies at the edge. Resource bounds do not replace edge throttling.

## Failure semantics and observability

- Connector header violations happen before servlet execution and may not create `http.server.requests` observations. Use edge status/connection counters and sanitized Tomcat parser logs; never log rejected values.
- Edge body-limit and upload-time rejections use the edge's response/connection contract. The application does not promise 413, and these are not visible to application metrics unless the edge exports them.
- Malformed/unreadable JSON reaches Spring MVC conversion handling and gets the existing sanitized 400 response. DTO constraint failures get the existing sanitized validation 400 after conversion.
- A body-read timeout may close the connection or surface as I/O/error response depending on progress. It is not an execution timeout and need not invoke a controller.
- Form, multipart, and swallowed-body limits have container-specific pre-controller behavior and are not current endpoint contracts. Do not advertise a uniform status.

Standard `http.server.requests` and `tomcat.global.*` cover requests reaching their instrumentation points. Pre-servlet rejection and disconnected/slow clients need edge, connector, and host evidence. No metric, unbounded tag, or alert threshold is added.

## Production evidence gate

Before routing traffic, retain reviewable edge evidence for the chosen maximum body bytes, equivalent handling of chunked/fixed-length bodies, early rejection without forwarding, absolute upload-duration/minimum-rate policy where required, sanitized logs, and fixed-cardinality metrics. Test the largest legitimate JSON and Stripe fixtures plus oversized and slow uploads. Keep the ceiling coherent with edge buffering and replica heap/concurrency; a streaming edge that forwards before enforcing its final ceiling does not establish a pre-application bound.
