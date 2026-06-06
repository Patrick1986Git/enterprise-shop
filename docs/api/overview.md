# API overview

This inventory is based on the current Spring MVC controllers and security configuration. Access levels describe the intended authorization model; method-level annotations and the security filter chain remain the source of truth.

## Authentication

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/login` | Public | Authenticate a user and return JWT authentication data. |
| `POST` | `/api/v1/auth/register` | Public | Register a new user account. |

## System/root/status

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1` | Public | Root API probe/message. |
| `GET` | `/api/v1/system/status` | Authenticated | Return application status information. |

## Users/current user/admin users

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/me` | Authenticated | Return the authenticated user's profile. |
| `GET` | `/api/v1/admin/users` | Admin | List users. |
| `GET` | `/api/v1/admin/users/{id}` | Admin | Return user details by id. |
| `PUT` | `/api/v1/admin/users/{id}` | Admin | Update a user. |
| `DELETE` | `/api/v1/admin/users/{id}` | Admin | Delete a user. |

## Categories/public

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/categories` | Public | List categories. |
| `GET` | `/api/v1/categories/slug/{slug}` | Public | Return a category by slug. |

## Categories/admin

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/admin/categories/{id}` | Admin | Return a category by id. |
| `POST` | `/api/v1/admin/categories` | Admin | Create a category. |
| `PUT` | `/api/v1/admin/categories/{id}` | Admin | Update a category. |
| `DELETE` | `/api/v1/admin/categories/{id}` | Admin | Delete a category. |

## Products/public

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/products` | Public | List products. |
| `GET` | `/api/v1/products/category/{categoryId}` | Public | List products by category. |
| `GET` | `/api/v1/products/slug/{slug}` | Public | Return a product by slug. |
| `GET` | `/api/v1/products/search` | Public | Search/filter products. |

## Products/admin

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/admin/products/{id}` | Admin | Return a product by id. |
| `POST` | `/api/v1/admin/products` | Admin | Create a product. |
| `PUT` | `/api/v1/admin/products/{id}` | Admin | Update a product. |
| `DELETE` | `/api/v1/admin/products/{id}` | Admin | Delete a product. |

## Reviews

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/products/{productId}/reviews` | Public | List reviews for a product. |
| `POST` | `/api/v1/reviews` | Authenticated | Create a product review. |
| `DELETE` | `/api/v1/reviews/{reviewId}` | Authenticated | Delete a review owned/allowed for the current user. |

## Cart

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/me/cart` | Authenticated | Return the authenticated user's cart. |
| `POST` | `/api/v1/me/cart/items` | Authenticated | Add a product to the cart. |
| `PATCH` | `/api/v1/me/cart/items/{productId}` | Authenticated | Update a cart item quantity. |
| `DELETE` | `/api/v1/me/cart/items/{productId}` | Authenticated | Remove a product from the cart. |
| `DELETE` | `/api/v1/me/cart` | Authenticated | Clear the cart. |

## Orders/current user/admin/shared order read

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/me/orders` | Authenticated | List the authenticated user's orders. |
| `POST` | `/api/v1/me/orders/checkout` | Authenticated | Checkout the authenticated user's cart and create a payment intent. |
| `GET` | `/api/v1/orders/{id}` | Authenticated | Return detailed order data when the requester is allowed to read it. |
| `GET` | `/api/v1/admin/orders` | Admin | List orders for admin review. |

## Stripe webhook

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/v1/webhooks/stripe` | Public HTTP route | Receive Stripe webhook payloads. Signature verification is performed in service logic. |

## OpenAPI/Swagger

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api-docs` | Public | OpenAPI JSON document path configured by SpringDoc. |
| `GET` | `/api-docs/**` | Public | Additional OpenAPI resources. |
| `GET` | `/v3/api-docs/**` | Public | SpringDoc compatibility OpenAPI resources. |
| `GET` | `/swagger-ui/**` | Public | Swagger UI resources. |
| `GET` | `/swagger-ui.html` | Authenticated unless covered by SpringDoc redirect/resource handling | Swagger UI path configured in `application.yml`; the explicit public matcher is `/swagger-ui/**`. |

## Actuator

Actuator endpoints are exposed by configuration and protected by `SecurityConfig`.

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/actuator/health` | Public | Health endpoint. |
| `GET` | `/actuator/info` | Admin | Application info endpoint. |
| `GET` | `/actuator/metrics` | Admin | Metrics index. |
| `GET` | `/actuator/prometheus` | Admin | Prometheus scrape endpoint. |
