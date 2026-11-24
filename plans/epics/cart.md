# Epic: Cart

Phase 4 · repository `shop-api` · areas `contract`, `product`, `cart-service`, `gateway`, `infra` ·
tracking issue: _created with this PR_

## Goal

Serve the cart from `cart-service` exactly as contract `cart.yaml` specifies it: a Redis hash per
cart with atomic add / update / remove, guest carts identified by the `guest_cart` cookie and merged
into the user cart after login, responses enriched with name, image, current price and stock level
from a cached product lookup in `core`, and a write-behind copy of every cart in Postgres through
Redpanda. The read and write paths stay on Redis only (no synchronous Postgres call), so the cart
meets p95 under 50 ms locally under k6. shop-web's Cart epic (Phase 4) builds the cart page on it.

## Scope

In scope:

- An additive contract release `1.3.0`: the list price and stock level on `CartItem`, a per-cart
  line limit, and an internal batch product lookup in `core` (see Contract impact).
- The internal lookup in the `product` module of `core`.
- In `cart-service`: access-token verification, caller identity (user or guest) and the guest
  cookie; the Redis cart with Lua scripts; the cached product lookup and the `product.updated`
  consumer; the seven cart operations with contract tests, including every documented error; the
  `cart.updated` publication and the write-behind consumer into the `cart` database.
- A gateway test that the cart route passes the `guest_cart` cookie both ways and keeps the
  `Origin` check.
- Demo wiring: the `cart` database, topics, settings, `make demo-smoke-cart`, and a k6 cart
  scenario with a p95 threshold.

Out of scope: vouchers and promotions (no contract operation; the artboard's voucher box is
answered in shop-web's epic); shipping fees (Checkout, Phase 5); the authoritative price and stock
check and stock reservation (Checkout, Phase 5); restoring a Redis cart from the Postgres copy
(open question 1); circuit breakers, cache warm-up and toxiproxy scenarios (Peak readiness,
Phase 6); a `cart_count` hint cookie (shop-web Phase 6); any frontend work.

## Decisions

See `docs/handoff/technical-notes.md` § 3 (per-user caching lives in backend Redis, short TTL,
invalidated on write), § 6 (gateway forwards `Authorization: Bearer`) and § 7 (every outbound call
has a budget and a fallback; no rate limit on the cart). The rules in `cart-service/CLAUDE.md`
apply: never read the core database; every outbound call has a timeout; concurrency is tested.
This epic is the "epic decision" that `cart-service/CLAUDE.md` requires for the Redis key layout.

| Topic | Decision |
| --- | --- |
| Identity | `cart-service` is an OAuth2 resource server: it verifies `Authorization: Bearer` with the JWKS of `core` (`CORE_URL/.well-known/jwks.json`, cached), issuer `AUTH_JWT_ISSUER` and audience `AUTH_JWT_AUDIENCE` (same defaults as core and the gateway). The gateway already verifies the cookie, but shop-web's server calls `cart-service` directly over the internal network (shop-build-plan "Request paths"), so the service must not trust an unverified token. The user id is the `sub` claim. An invalid or expired Bearer is 401 `AUTH_UNAUTHENTICATED`. Without a Bearer the caller is a guest, **except** when the request carries the `logged_in=1` hint cookie: then every cart operation answers 401 `AUTH_UNAUTHENTICATED` (owner decision 2026-11-24). Reason: the access cookie expires after 10 minutes (`Max-Age=600`) and the gateway strips an invalid one, so a signed-in browser whose token expired reaches `cart-service` without a Bearer; treating it as a guest would put its writes into a new guest cart that the user cart never sees (merge runs only at login). The 401 makes shop-web's single-flight refresh run and retry the request with a fresh token; a failed refresh clears the hint (Phase 2), after which the caller is a real guest. The hint is used only to refuse guest mode, never to grant access, so a forged hint can only cause a 401. shop-web's server forwards only `guest_cart` (never `logged_in`), so SSR is unaffected. |
| Guest cookie | The first write without a token creates a guest id of 128 random bits (`SecureRandom`, base64url, 22 characters) and sets `guest_cart` as the contract's `GuestCartSet` header says (`Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=2592000`, always `Secure` like the auth cookies in `SessionCookies`; browsers accept it on `http://localhost`). A `guest_cart` value that does not match `^[A-Za-z0-9_-]{22}$` is treated as no cookie (never 400: the cookie is httpOnly, so the browser could not clear it and every cart request would fail), counted as `cart.guest_cookie.invalid`, and replaced by a new id on the next write; a well-formed id with no cart is an empty cart. When the caller is signed in, `guest_cart` is ignored except by merge (contract). Reads never create a guest id. |
| Redis key layout | One hash per cart: `cart:{userId}` for users (as in the plan) and `cart:guest:{guestId}` for guests. Fields `q:{sku}` = quantity and `t:{sku}` = the time the SKU was first added (epoch millis, items are listed newest first), plus `updatedAt` (epoch millis) and `version` (incremented by every write). Guest carts expire 30 days after the last write (the cookie's lifetime); user carts 90 days after the last write. |
| Atomic writes | Every write is one Lua script (`EVALSHA`, loaded at start-up) that checks the limits, changes the fields, bumps `version` and `updatedAt` and refreshes the TTL, so two concurrent requests never lose an update. Add: `q = q + quantity`, more than 10 → 422 `CART_QTY_LIMIT` and nothing changes (contract example: "at most 10 units of one SKU"). Update: absolute quantity 1–10, unknown SKU → 404 `CART_ITEM_NOT_FOUND`. Remove: unknown SKU → 404 `CART_ITEM_NOT_FOUND`. A cart holds at most 50 distinct SKUs (the `CreateReservationRequest.items` maximum, so every cart can go to checkout): a new SKU beyond 50 → 422 `CART_ITEMS_LIMIT` (new code, see Contract impact). |
| Merge on login | `POST /api/cart/merge` runs one Lua script over both keys: quantities of the same SKU are summed and capped at 10, the user cart keeps the earlier `t:{sku}`, SKUs beyond the 50-line limit are dropped (newest guest lines first) and the guest key is deleted; the response clears `guest_cart` (`Max-Age=0`). Dropped lines are counted (`cart.merge.dropped_lines`); the response shape has no field for them (open question 2). Without the cookie, or for an unknown guest id, the user cart is returned unchanged (and the cookie still cleared when it was sent). Merging twice is harmless: the second call finds no guest key. Merge publishes `cart.updated` for both keys (the user cart, and an empty snapshot for the guest key so its `cart_snapshot` row is deleted). shop-web calls it after a successful login. |
| Product lookup | New internal operation in `core`: `POST /internal/products/lookup` with `{skus}` (1–50) → `{items}` with, per known SKU, `sku`, `slug`, `name`, `imageUrl` (first image), `variantLabel` (the variant options joined as `Màu: Đen nhám`, as on the artboard, with a fixed Vietnamese label per option name in the `product` module: `color` → `Màu`, `size` → `Kích cỡ`, any other name used as is; options separated by ` · `; absent for single-variant products), `price` (the current availability price, so the sale price during a flash sale), `listPrice` (only when higher than `price`, as in `ProductSummary`) and `stockLevel`; unknown SKUs are left out. `x-internal: true`, `X-Internal-Token` like `PATCH /internal/products/{sku}`, never routed by the gateway (Phase 3 gateway test covers `/internal/**`). |
| Product cache | `cart-service` caches lookup results in Redis, one key `product:{sku}` (JSON) with TTL 60 s, so all instances share it and a burst produces one core call per SKU. A cart read fetches only the missing SKUs, in one lookup call with a 150 ms timeout (technical notes § 7: budgets of 150–300 ms) over a pooled HTTP client. The TTL bounds the staleness when a flash sale starts or ends without a product change (Product epic, Risks). |
| `product.updated` | `cart-service` consumes the topic with consumer group `cart-service` and deletes `product:{sku}` for every event (price, stock and detail all change the enriched line). Delivery is at least once and deleting is idempotent, so no deduplication store is needed. Consumer lag never blocks a request. |
| Enriched response | Lines are built at read time from the Redis quantities and the product cache: `unitPrice` = current `price`, `lineTotal` = `unitPrice × quantity`, `listPrice` and `stockLevel` copied, `available` = `stockLevel != OUT_OF_STOCK`. A SKU the lookup no longer knows stays in the cart as a line with `available: false`, `name` = the SKU, `slug` = the SKU lower-cased, `imageUrl` = `CART_PLACEHOLDER_IMAGE_URL` and price 0, so the user can see and remove it (open question 3). `itemCount` = sum of quantities of all lines; `subtotal` = sum of `lineTotal` of available lines. `GET /api/cart/summary` reads only the Redis hash (no product lookup), so the header badge never waits on `core`. Every response is `Cache-Control: private, no-store` (contract). |
| Stock on add | Add and update check the cached `stockLevel` (fetched if missing): `OUT_OF_STOCK` → 409 `CART_OUT_OF_STOCK`; unknown SKU → 404 `PRODUCT_NOT_FOUND` on add. This is advisory: the authoritative check and the reservation are Checkout (Phase 5). |
| Core unavailable | Lookup timeout or error with SKUs missing from the cache: `GET /api/cart` → 503 `SERVICE_UNAVAILABLE` with `Retry-After: 2` (shop-web then shows the saved cart and the stale banner); add / update of a SKU that is not cached → 503 and nothing changes; remove, merge and summary never call `core`. Circuit breakers are Phase 6. Counter `cart.product_lookup.failed` and timer `cart.product_lookup` are exported for the Phase 6 dashboards. |
| Write-behind | After every successful write (add, update, remove, merge), `cart-service` builds a snapshot from the hash the Lua script returns (so `version` and items always belong together, even under concurrent writes) and sends it as a `CartUpdated` record to topic `cart.updated` (key = cart key, value = JSON `{cartKey, version, updatedAt, items: [{sku, quantity, addedAt}]}`) without waiting for the broker on the request thread (async send, `acks=all`, failures counted as `cart.write_behind.failed` and logged; the next write carries the full cart again). A consumer in `cart-service` (group `cart-writer`) upserts it into table `cart_snapshot(cart_key PK, version, items JSONB, updated_at)` only when `version` is higher than the stored one, so duplicates and reordering are harmless; an empty cart deletes the row; an expired guest key leaves its row (cleanup is Phase 6). |
| Cart database | `cart-service` gets its own database `cart` and role `cart` in the demo Postgres (created by an init script), with Flyway in `cart-service` (location `db/migration`, default history table). It never connects to the `shop` database of `core`, so the rule "never read the core database" holds. The datasource is used only by the write-behind consumer. Settings `CART_DB_URL`, `CART_DB_USER`, `CART_DB_PASSWORD` with local defaults. |
| `CartUpdated` is internal | Producer and consumer are both `cart-service`, so the record shape lives in `cart-service` and is not added to the contract. Checkout (Phase 5) takes its lines from the request body (`CreateReservationRequest`), not from this topic. |
| Errors | RFC 9457 problem details with `code` and `traceId` for every error, including validation (400 `VALIDATION_FAILED`) and Redis down (503 `SERVICE_UNAVAILABLE`), `Cache-Control: no-store`. |
| Gateway | No functional change is expected: `/api/cart/**` already routes to `cart-service`, is not rate limited and is `Origin`-checked. One task adds tests that the `guest_cart` request cookie and the `Set-Cookie` response header pass through, that an invalid access cookie on a cart route arrives without a Bearer while the `logged_in` cookie is passed through unchanged (so `cart-service` can answer 401, see Identity), and fixes the gateway only if a test fails. |
| Load target | k6 scenario `infra/peak/k6/cart.js` against `make demo` through the gateway: 50 virtual users for 60 s, each signed in as a seeded demo user (or a guest), mixing 60 % `GET /api/cart`, 20 % summary, 15 % add, 5 % update. Adds stay within the limits (each VU rotates over the seeded SKUs with quantity 1 and removes a line when it reaches 10 units; the remove is not counted in the mix), and the scenario's `responseCallback` accepts only 2xx, so any 4xx counts as an error; thresholds `http_req_duration{group:cart} p(95) < 50` and error rate < 1 %. `make demo-k6-cart` runs it and fails on a threshold breach. The SLO is measured locally as the plan says, not in CI. |
| Demo between tasks | From task 3 until task 11, `cart-service` in `make demo` runs with local defaults (`CORE_URL=http://localhost:8081`), so signed-in cart calls in the demo fail until the infra task wires the settings. CI is not affected; the order keeps one area per task. |
| Dependencies | `spring-boot-starter-oauth2-resource-server`, `spring-kafka`, `spring-boot-starter-jdbc` (or Data JDBC), Flyway and the Postgres driver for `cart-service`, Testcontainers Redis, Redpanda and Postgres modules for its tests; refreshed with `make lock`. |
| Sensitive paths | The identity task adds token verification to `cart-service` (a `security/` package, reviewed with the security checklist; not protected). The gateway task changes only tests unless a test fails. No task touches a protected path. |

## Contract impact

Additive changes, released as `1.3.0` in the first task (minor bump; no breaking change):

- `CartItem`: optional `listPrice` (`Money`, only when higher than `unitPrice`) and optional
  `stockLevel` (`#/components/schemas/StockLevel`, the same enum as `Availability`, copied into
  `cart.yaml` because the specs share only `errors.yaml`; or moved into `errors.yaml` if the
  contract task finds that cleaner). The Cart artboard shows a struck price and "Sắp hết" / "Còn
  hàng" per line, which the current `CartItem` cannot carry. The `CartWithItems` example is updated
  to the seed numbers (ANC Pro 890 000, struck 1 790 000, `LOW`).
- Error code `CART_ITEMS_LIMIT` (422, more than 50 distinct SKUs) in `x-error-codes` and as a 422
  example on `addCartItem`.
- `POST /internal/products/lookup` in `core.yaml` (`x-internal: true`, `security: []`,
  `X-Internal-Token`, request `ProductLookupRequest`, response `ProductLookupResponse`, problems
  400 / 404).
- `getCart`, `getCartSummary`, `addCartItem`, `updateCartItem` and `removeCartItem` document 401
  `AUTH_UNAUTHENTICATED` for an invalid Bearer, and for no Bearer with the `logged_in=1` hint
  cookie (identity decision); today only merge lists 401. The `cart.yaml` description says the
  same.
- The `GuestCart` parameter description says a malformed value is ignored, and the `GuestCartSet`
  example uses a 22-character base64url id (today's `8c1f0f6e9a3b4d2e` does not match the format).

shop-web pins `1.3.0` in its Cart epic.

## Expected tasks (the planner details them after approval)

1. [contract] `CartItem.listPrice` and `stockLevel`, `CART_ITEMS_LIMIT`, 401 on cart operations,
   `POST /internal/products/lookup`; release `1.3.0` (S).
2. [product] `POST /internal/products/lookup` in `core` with its contract test (S).
3. [cart-service] Resource server with JWKS, caller identity (including 401 for no Bearer with
   `logged_in=1`), guest id and cookie (malformed → ignored), problem-details error handling (S).
4. [cart-service] Redis cart repository: key layout, Lua scripts for add / set / remove / merge,
   TTLs, concurrency tests (M).
5. [cart-service] Product lookup client with the Redis product cache, timeout, metrics, and the
   `product.updated` consumer (M).
6. [cart-service] `GET /api/cart` and `GET /api/cart/summary` with the enriched response and
   contract tests (M).
7. [cart-service] `POST /api/cart/items`, `PATCH` and `DELETE /api/cart/items/{sku}` with limits,
   stock check and contract tests for every documented error (M).
8. [cart-service] `POST /api/cart/merge` with contract tests (S).
9. [cart-service] `cart.updated` publication, the `cart` database with Flyway, and the
   write-behind consumer (M).
10. [gateway] Tests for the cart route: guest cookie both ways, invalid access cookie stripped with
    `logged_in` kept, `Origin` check (S).
11. [infra] Demo wiring: `cart` database init script, topics `cart.updated`, cart-service settings
    (`INTERNAL_API_TOKEN`, JWKS, issuer and audience, database), `make demo-smoke-cart` (S).
12. [infra] k6 cart scenario with the p95 threshold and `make demo-k6-cart` (S).

Dependencies: 1 first; 2 after 1; 3 after 1; 4 after 3; 5 after 1 and 2; 6 after 4 and 5; 7 after
6; 8 after 4 and 6; 9 after 7 and 8; 10 after 3; 11 after 9; 12 after 11. Tasks 4 and 5 can run in
either order. Tasks 3, 5 and 9 change dependencies (`make lock`); none touches a protected path.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Lost updates under concurrent writes (double click, two tabs) | Every write is one Lua script; Testcontainers tests fire 20 parallel adds of 1 unit and expect quantity 10 and the rest 422, and parallel set / remove keep a consistent hash. |
| `core` slow or down makes the cart page slow | 150 ms lookup budget, shared 60 s product cache, summary and remove without `core`; 503 lets shop-web fall back to the saved cart. Circuit breakers in Phase 6. |
| Stale price shown in the cart | `product.updated` deletes the cache entry; TTL 60 s bounds sale-window changes; checkout confirms prices (`priceChanged`, Phase 5). |
| Forged or guessed guest ids read another guest's cart | 128-bit random ids, strict format check, httpOnly cookie; a signed-in caller never reads a guest cart except through merge. |
| A signed-in user with an expired token writes into a guest cart | No Bearer plus `logged_in=1` → 401, shop-web refreshes and retries; tested in `cart-service` (hint + no token → 401, no hint → guest) and end to end in shop-web's Cart epic. |
| The internal service port trusts an unverified token | `cart-service` verifies every Bearer itself with the JWKS (identity decision), tested with a wrong key, wrong issuer, wrong audience and an expired token. |
| Write-behind loses or reorders updates | Full snapshot per record with a monotonic `version`; the consumer ignores older versions; failures are counted, and the next write repairs the copy. |
| Postgres down at peak | Requests never touch Postgres; only the consumer does, and it retries with backoff while the topic retains the records. |
| p95 target missed because of the product lookup | Lookups only for cache misses and batched per cart; the k6 scenario warms the cache in its setup and reports misses (`cart.product_lookup` timer). |
| Testcontainers (Redis, Redpanda, Postgres) cannot run in cloud sessions | They run in CI, where skipped tests fail the build; workers wait with `checks-wait`. |

## Open questions for the owner

1. **Restore from Postgres.** The plan asks for write-behind but not for reading it back. Proposed:
   in Phase 4 the Postgres copy is a durable record only (no read-through on a Redis miss, because
   every empty-cart user would then hit Postgres at peak); a restore job for a lost Redis is a
   Phase 6 task. Alternative: read-through for signed-in users on a miss, with a 50 ms budget.
2. **Line limit.** 50 distinct SKUs per cart (matches checkout's `maxItems`), new error
   `CART_ITEMS_LIMIT`. Alternative: no line limit, and checkout handles more than 50 lines.
3. **Unknown SKUs in a cart** (product removed from the catalog): kept as an unavailable line with
   the SKU as name and price 0, as proposed, or dropped silently on the next read.

## Done when

- After `make demo`, all seven cart operations work through the gateway for a guest and for a
  signed-in demo user, and every operation and its documented errors has a passing contract test.
- A Testcontainers test with 20 concurrent adds of one SKU ends with quantity 10 and the other
  requests answered 422 `CART_QTY_LIMIT`.
- A guest who adds two items, logs in and calls merge has them in the user cart, and the response
  clears `guest_cart`.
- A cart request with the `logged_in=1` cookie and no Bearer is answered 401 and creates no guest
  cart; a malformed `guest_cart` is treated as no cookie.
- Changing a price with `PATCH /internal/products/{sku}` shows the new `unitPrice` on the next
  `GET /api/cart` (after the `product.updated` record is consumed), tested with Testcontainers.
- A write produces one `cart.updated` record and, after it is consumed, a `cart_snapshot` row with
  the same `version`.
- `make demo-smoke-cart` passes, `make demo-k6-cart` reports p95 under 50 ms on the owner's machine,
  and `make lint test` is green on `main`.
