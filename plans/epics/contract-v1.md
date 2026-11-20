# Epic: Contract v1

Phase 1 · repository `shop-api` · area `contract` · tracking issue: _created with this PR_

## Goal

Publish the first complete API contract, `@shop/api-contract@1.0.0`, so the backend phases (2–5)
implement it and `shop-web` generates its types, client and mocks from it. The contract is the
source of truth: services implement it exactly, and every endpoint gets a contract test.

## Scope

In scope:

- `contracts/openapi/errors.yaml`: problem types, the error-code catalogue, shared responses.
- `contracts/openapi/core.yaml`: auth, products (catalog, detail, flash sale, member price,
  recommendations), checkout (reservations, shipping and payment options) and orders.
- `contracts/openapi/cart.yaml`: cart read, summary, add / update / remove item, merge on login.
- Shared conventions as reusable components (see Decisions).
- A breaking-change check in CI and the rule that breaking changes need a major version.
- A bundled single-file spec per service in the released package, and the 1.0.0 release.

Out of scope: implementing any endpoint (Phases 2–5); event schemas for Redpanda (decided in the
Product and Cart epics); admin APIs.

## Endpoints (to be detailed in the tasks)

All public paths start with `/api` and reach the services unchanged through the gateway
(`/api/cart/**` → cart-service, other `/api/**` → core).

| Area | Operations |
| --- | --- |
| Auth (core) | `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/me`; `GET /.well-known/jwks.json` (internal, for the gateway) |
| Products (core) | `GET /api/products` (category filter, cursor pagination), `GET /api/products/{slug}`, `GET /api/products/{sku}/availability` (price and approximate stock, short cache), `GET /api/flash-sales/current`, `GET /api/products/{slug}/member-price` (auth), `GET /api/recommendations` (auth optional) |
| Cart (cart-service) | `GET /api/cart`, `GET /api/cart/summary` (badge), `POST /api/cart/items`, `PATCH /api/cart/items/{sku}`, `DELETE /api/cart/items/{sku}`, `POST /api/cart/merge` |
| Checkout (core) | `POST /api/checkout/reservations` (10-minute stock reservation), `GET /api/checkout/reservations/{id}`, `GET /api/checkout/shipping-options`, `GET /api/checkout/payment-methods` |
| Orders (core) | `POST /api/orders` (requires `Idempotency-Key`), `GET /api/orders/{id}` |

## Decisions

| Topic | Decision |
| --- | --- |
| Spec format | OpenAPI 3.1, one file per deployable plus `errors.yaml`; shared parts referenced with `$ref` across files. |
| Errors | RFC 9457 `application/problem+json` with a stable `code` (`^[A-Z][A-Z0-9_]*$`), for example `CART_QTY_LIMIT`, `AUTH_INVALID_CREDENTIALS`, `CHECKOUT_RESERVATION_EXPIRED`, `IDEMPOTENCY_KEY_REUSED`. Every operation documents its 4xx codes. |
| Auth | Browser: httpOnly cookies `access_token` / `refresh_token` (security scheme `cookieAuth`); tokens never appear in response bodies sent to the browser. Gateway → services: `Authorization: Bearer` (`bearerAuth`). |
| Money | Integer amounts in VND (no minor unit), as `{ "amount": 890000, "currency": "VND" }`. |
| Identifiers | Products by `sku` (string) and `slug`; orders and reservations by UUID. |
| Pagination | Cursor based: `?cursor=&limit=` → `{ items, nextCursor }`. |
| Idempotency | `Idempotency-Key` header (UUID) required on `POST /api/orders`; a replay with the same key and body returns the original response; a different body returns 422 `IDEMPOTENCY_KEY_REUSED`. |
| Caching hints | Read endpoints document `Cache-Control` and the tags the frontend uses (`product-{id}`, `flash-sale`). |
| Versioning | Semantic versioning in `contracts/package.json`; all specs share the version. Breaking changes need a major bump, which `merge-gate` treats as sensitive (owner approval). |
| Breaking-change check | `oasdiff breaking` (Docker image) compares each spec with `main` in CI and fails when a breaking change comes without a major bump. |
| Release | `contract-publish` creates the GitHub Release `contract-v<version>` with the `npm pack` tarball; pre-releases `0.x` are allowed during the epic so shop-web can start early. |

## Expected tasks (the planner details them after approval)

1. Conventions and shared components: money, pagination, ids, security schemes, `Idempotency-Key`, error-code catalogue in `errors.yaml` (S).
2. Breaking-change check with oasdiff in CI, plus the major-bump rule (S, `infra-change`).
3. Auth endpoints (M).
4. Product and flash-sale endpoints (M).
5. Cart endpoints in `cart.yaml` (M).
6. Checkout reservations, shipping and payment options (M).
7. Orders with idempotency (S).
8. Bundled single-file specs in the package and release `1.0.0` (S).

Tasks 3–7 depend on 1; task 8 depends on all others. Each task bumps the pre-release version
(`0.2.0`, `0.3.0`, …) so shop-web can consume partial contracts.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| The contract misses something a later phase needs | Additive changes are minor versions; workers open a proposal issue (`area:contract`) instead of editing the spec in feature tasks. |
| Contract and implementation drift | Every endpoint task in Phases 2–5 adds a contract test that validates responses against the spec. |
| Cross-file `$ref` breaks generators in shop-web | The package ships bundled single-file specs (task 8); shop-web generates from those. |
| Over-specifying too early | Keep schemas to what the design and phase "done when" need; mark optional fields as optional. |

## Done when

- `@shop/api-contract@1.0.0` is released as `contract-v1.0.0`.
- `make lint-contract` and the breaking-change check pass on `main`.
- Every operation has an `operationId`, tag, request and response examples, and documented 4xx
  problem codes.

## Open questions for the owner

- Should guest carts be addressed by a `guest_cart` cookie (proposed) or by a header?
- Are recommendations personalised in the MVP, or a static "related products" list per product?
