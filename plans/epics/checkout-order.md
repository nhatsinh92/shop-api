# Epic: Checkout + order

Phase 5 · repository `shop-api` · areas `contract`, `product`, `checkout`, `order`, `gateway`,
`infra` · tracking issue: _created with this PR_

## Goal

Let a signed-in user turn cart lines into an order exactly once: `POST /api/checkout/reservations`
confirms the price (flash sale and member tier included) and the stock of every line and holds the
stock for 10 minutes; an expired or replaced reservation gives its stock back; `POST /api/orders`
places the order from an active reservation once per `Idempotency-Key`, with cash on delivery or an
online payment through the mock payment gateway (success, failure, timeout); a failed payment gives
the stock back. Everything matches contract `core.yaml` (§ checkout, § orders). shop-web's Checkout
epic (Phase 5) builds the checkout page on it.

## Scope

In scope:

- An additive contract release `1.4.0` (see Contract impact).
- A public stock-hold API in the `product` module: confirm prices and take or give back stock
  atomically, publishing the existing `ProductUpdated` (`STOCK`) events.
- In the `checkout` module: reservations (create, read, one active reservation per user, expiry
  sweep), shipping options, payment methods, and the payment client for the mock gateway.
- In the `order` module: order placement with the idempotency store, the COD and online payment
  paths, `GET /api/orders/{id}`, and recovery of orders whose payment never resolved.
- Gateway rate limits on the two state-changing checkout operations, and tests that the checkout
  headers pass through.
- Demo wiring: core settings for the mock payment gateway and `make demo-smoke-checkout`.

Out of scope: vouchers and promotions (no contract operation; shop-web's Cart epic decided not to
render them); a waiting room (Phase 6, technical notes § 7); circuit breakers on the payment client
and k6 checkout scenarios (Peak readiness, Phase 6); order history, cancellation and refunds (no
contract operation; `CANCELLED` stays unused); emails ("Đã gửi xác nhận qua email" on the artboard);
removing ordered lines from the cart in `cart-service` (open question 3); real payment providers,
redirects and webhooks (the mock answers synchronously, so `redirectUrl` is never set); any
frontend work.

## Decisions

See `docs/handoff/technical-notes.md` § 6 (gateway: JWT, `Origin` check, Redis rate limits) and § 7
(rate limits sit at checkout, never on the cart; every outbound call has a budget). The rules in
`core/CLAUDE.md` apply: modules talk through the types in their root package and application
events; each module owns its own schema; `ModularityTests` and `ArchitectureTest` stay green.

| Topic | Decision |
| --- | --- |
| Module roles | `product` owns stock and prices and exposes `StockHolds` (root package). `checkout` owns reservations, shipping options, payment methods and the payment client, and exposes `Reservations`, `ShippingOptions` and `Payments` (root package). `order` owns orders and the idempotency store and uses the `checkout` API; `checkout` never calls `order`. Dependencies point one way: `order → checkout → product`. |
| Identity | Every checkout and order operation needs a valid token (contract `security`); the user id is the `sub` claim and the tier the `tier` claim of Spring Security's `JwtAuthenticationToken`, read as the member-price task did, so neither module imports `auth`. Another user's reservation or order is 404 (contract). |
| Schemas | `checkout` uses schema `checkout` (`db/migration/checkout`): `reservations(id uuid PK, user_id, status, created_at, expires_at, released_at, restocked_at)` and `reservation_items(reservation_id, sku, quantity, unit_price, base_price, price_changed, name, variant_label, image_url)`. `order` uses schema `orders` (`order` is an SQL keyword; `db/migration/orders`): `orders(id uuid PK, number, user_id, reservation_id UNIQUE, status, subtotal, shipping_option, shipping_fee, total, payment_method, payment_status, shipping_address jsonb, note, created_at, updated_at)`, `order_items(order_id, sku, quantity, unit_price, line_total)` and `idempotency_keys(user_id, key, request_hash, state, order_id, response_status, response_body jsonb, created_at, PK (user_id, key))`. Money is `bigint` VND as in `product`. Each gets its own Flyway instance as `core/CLAUDE.md` § Migrations describes; the first task that adds one (task 3) merges the single `FlywayMigrationStrategy` so it migrates auth, product, modulith, checkout and orders in that order, with a migration test that merged histories are not re-applied. |
| Stock hold | `StockHolds.hold(lines, tier, now)` runs in the caller's transaction: lines sorted by SKU (no deadlocks), one `UPDATE product.stock SET quantity = quantity - :q WHERE sku = :sku AND quantity >= :q` per line; any line short → nothing is taken and the result lists every short line with `requested` and `available` (unknown SKU: `available` 0). `release(lines)` adds the quantities back. Both publish `ProductUpdated` with `changes = [STOCK]` per SKU after commit (stock-only: no webhook, Product epic), so `cart-service` and the web caches see the new level. |
| Flash sale stock | When a SKU is in the current sale (`PriceRule`), a hold also increments `flash_sale_items.sold` if `sold + q <= sale_stock`, and the line gets the sale price. When the sale stock is short, the line is priced at the variant price instead (it still holds regular stock) and the result says so; `release` decrements `sold` only for lines that took sale stock (stored per reservation line). Open question 2. |
| Confirmed price | `unitPrice` = the member price of the caller's tier applied to the current price (`PriceRule.currentPrice`, then the Phase 3 tier discount rounded down to 500 VND), so the PDP member price and the checkout price agree (Product epic risk; contract example 845 500 = GOLD of 890 000). The rule moves into the `product` public API in task 2 if it is not there yet; member price keeps using it. |
| `priceChanged` | The request may carry the price the client showed per line (`expectedUnitPrice`, new optional field, see Contract impact), which is the cart's `unitPrice` (current price without the member discount). `priceChanged` is true when it is given and differs from the current price before the member discount; without it, `priceChanged` is false. A member discount alone never sets it. |
| Reservation | `POST /api/checkout/reservations`: duplicate SKUs in `items` → 400 `VALIDATION_FAILED`. In one transaction: the caller's other `ACTIVE` reservations become `RELEASED` and their stock is released (one active reservation per user, so repeated clicks cannot hoard stock), then the new lines are held; any short line → 409 `CHECKOUT_OUT_OF_STOCK` with `unavailable` and nothing changes (the old reservation stays active). `expiresAt` = creation + 10 minutes exactly (contract), from an injected `Clock`. Display fields (`name`, `variantLabel`, `imageUrl`) come from the `product` lookup used by `/internal/products/lookup` and are stored with the line. |
| Expiry | Reads report `EXPIRED` as soon as `now >= expiresAt`, whatever the sweep has done. A sweep (`@Scheduled`, every 10 s, `CHECKOUT_SWEEP_INTERVAL`) takes up to 100 `ACTIVE` rows with `expires_at <= now` per run with `FOR UPDATE SKIP LOCKED` (safe with several core instances), marks them `EXPIRED` and releases their stock; counter `checkout.reservations.expired`. A reservation's stock is released at most once (`released_at`). |
| Shipping options | Configuration, not a table: `STANDARD` "Giao tiêu chuẩn", fee 0; `EXPRESS_2H` "Giao nhanh 2h", fee 35 000 (artboard and contract example). `estimatedDelivery` is computed in `Asia/Ho_Chi_Minh`: "Nhận {d+1}–{d+2}/{MM}" for standard (artboard "Nhận 13–14/12" on 12/12), "Nhận trong hôm nay (nội thành)" for express. A `reservationId` that is not the caller's → 404 `CHECKOUT_RESERVATION_NOT_FOUND`; otherwise the same list (fees do not depend on the items in the MVP). |
| Payment methods | `COD`, `EWALLET`, `CARD` with the contract's names; `available` is true unless the method is listed in `CHECKOUT_DISABLED_PAYMENT_METHODS` (default empty). Health-based availability belongs with the circuit breaker in Phase 6. |
| Payment client | `Payments.charge(orderId, amount, method, mockOutcome)` → `SUCCEEDED`, `FAILED` or `TIMED_OUT`. `POST {PAYMENT_URL}/payments` (default `http://localhost:8090`) over a pooled `RestClient`, connect timeout 500 ms, read timeout `PAYMENT_TIMEOUT` (default 3 s); 201 → `SUCCEEDED`, 402 → `FAILED`, read timeout → `TIMED_OUT`, any other error → `FAILED`. Timer `checkout.payment` and counter `checkout.payment.outcome{outcome}` for the Phase 6 dashboards. |
| Mock outcome | For the demo and the e2e journeys, `placeOrder` accepts an optional `X-Mock-Payment-Outcome: fail \| timeout` header (contract, documented as demo-only). It is forwarded to the mock as `X-Mock-Outcome` only when `PAYMENT_MOCK_OUTCOME_HEADER=true` (`make demo` sets it; the default is false, so a production configuration ignores it). It is part of the idempotency fingerprint. |
| Placement | `POST /api/orders`, after header and body validation (phone: `^(0\|\+84)[35789][0-9]{8}$`, stricter than the contract's length, answered as 400 `VALIDATION_FAILED` with the field, as the contract example shows): transaction 1 inserts the idempotency row (`IN_PROGRESS`), consumes the reservation through `Reservations.consume(id, userId, now)` (row lock; not the caller's → 404; `EXPIRED` or past `expiresAt` → 410 `CHECKOUT_RESERVATION_EXPIRED` and its stock is released; `RELEASED` → 410 `CHECKOUT_RESERVATION_EXPIRED`; `CONSUMED` → 410 `CHECKOUT_RESERVATION_CONSUMED`, new code), checks the payment method is available (else 422 `CHECKOUT_PAYMENT_METHOD_UNAVAILABLE`, new code) and creates the order from the reservation lines and the shipping fee. COD: status `PLACED`, payment `NOT_REQUIRED`, and the 201 response is stored in the same transaction (`COMPLETED`). Online: status `PENDING_PAYMENT`, payment `PENDING`; after commit the payment is charged, and transaction 2 sets `PAID`/`SUCCEEDED` or `PAYMENT_FAILED`/`FAILED`/`TIMED_OUT`, gives the stock back on failure (`Reservations.restock(id)`, at most once via `restocked_at`) and stores the 201 response. Any 4xx domain error rolls transaction 1 back, so the key leaves no row. |
| Idempotency | Keyed by `(user_id, Idempotency-Key)`; fingerprint = SHA-256 of the canonical JSON of the parsed body plus the mock-outcome header. A concurrent request with the same key blocks on the primary key until transaction 1 commits, then sees the row: same fingerprint and `COMPLETED` → the stored status and body with `Idempotent-Replayed: true`; `IN_PROGRESS` → 409 `IDEMPOTENCY_IN_PROGRESS`; different fingerprint → 422 `IDEMPOTENCY_KEY_REUSED`. Missing or non-UUID key → 400 `IDEMPOTENCY_KEY_MISSING` before anything else. Rows are deleted after 24 h by an hourly job. |
| Order number | Every order gets `number` = `MY-` + the order date in `Asia/Ho_Chi_Minh` as `MMdd` + `-` + a Postgres sequence value padded to at least 4 digits (artboard "#MY-1212-0087"); new required response field, see Contract impact. |
| Recovery | An order still `PENDING_PAYMENT` 2 minutes after creation (core restarted during the payment call) is set to `PAYMENT_FAILED`/`TIMED_OUT` by a job every 30 s (`FOR UPDATE SKIP LOCKED`), its stock is given back and its idempotency row is completed with the order's response, so a retry with the same key replays it. Counter `order.payment.recovered`. |
| Rate limits | The gateway adds per-user limits (key `sub`): `POST /api/checkout/reservations` 10 per minute and `POST /api/orders` 10 per minute (`GATEWAY_CHECKOUT_RATE`, tuned in Phase 6), on top of the existing core limit; exceeded → 429 `RATE_LIMITED` with `Retry-After`. The cart stays unlimited. |
| Errors | RFC 9457 problem details with `code` and `traceId`, `Cache-Control: no-store`; every documented error of the six operations has a contract test. |
| Sensitive paths | The gateway task changes rate-limit configuration and tests (security checklist, not protected). No task touches a protected path; none needs `infra-change`. |

## Contract impact

Additive changes, released as `1.4.0` in the first task (minor bump; `oasdiff` reports no breaking
change):

- `CreateReservationRequest.items[].expectedUnitPrice` (optional `Money`), and the `priceChanged`
  description rewritten to the rule above.
- `ReservationItem`: `name` and `imageUrl` (required, the reservation shows the lines without a
  second call) and `variantLabel` (optional), as in `ProductLookupItem`.
- `Order.number` (required string, pattern `^MY-[0-9]{4}-[0-9]{4,}$`) and the examples updated.
- `placeOrder`: optional header `X-Mock-Payment-Outcome` (`fail`, `timeout`), described as honoured
  only by demo configurations; 410 gains `CHECKOUT_RESERVATION_CONSUMED`; 422 gains
  `CHECKOUT_PAYMENT_METHOD_UNAVAILABLE`; both new codes in `x-error-codes`.
- The `OrderPlacedCod` example's `shippingFee` becomes 0 (`STANDARD` is free in the shipping-options
  example), and the `info.description` drops "Phase 1 pre-release".

shop-web pins `1.4.0` in its Checkout epic.

## Expected tasks (the planner details them after approval)

1. [contract] `expectedUnitPrice`, reservation display fields, `Order.number`, the mock-outcome
   header, the two new error codes, example fixes; release `1.4.0` (S).
2. [product] `StockHolds` public API: hold and release with the flash-sale stock rule, member price
   in the public API, `STOCK` events, concurrency tests (M).
3. [checkout] Schema and Flyway instance (merging the migration strategy), `POST` and
   `GET /api/checkout/reservations` with one active reservation per user and contract tests (M).
4. [checkout] Expiry: lazy status on read and the sweep that releases stock (S).
5. [checkout] `GET /api/checkout/shipping-options` and `GET /api/checkout/payment-methods` with
   contract tests (S).
6. [checkout] `Reservations.consume` / `restock` and the `Payments` client with timeouts, the
   mock-outcome header and metrics, tested against WireMock (M).
7. [order] Schema, idempotency store, `POST /api/orders` for COD, `GET /api/orders/{id}`, every
   documented error with contract tests (M).
8. [order] Online payment path, stock give-back on failure, the recovery job, key cleanup, and the
   double-submit concurrency test (M).
9. [gateway] Checkout rate limits; tests that `Idempotency-Key`, `X-Mock-Payment-Outcome` and
   `Idempotent-Replayed` pass through and the `Origin` check applies (S).
10. [infra] Demo wiring (`PAYMENT_URL`, `PAYMENT_MOCK_OUTCOME_HEADER=true`) and
    `make demo-smoke-checkout`: reserve, COD order, replay, failed and timed-out payment (S).

Dependencies: 1 first; 2 after 1; 3 after 2; 4 and 5 after 3; 6 after 3; 7 after 5 and 6; 8 after
4 and 7; 9 after 1; 10 after 8 and 9. Task 3 also changes the product module's migration
configuration (the merged strategy), which its issue lists under "May change". Task 6 adds no
dependency (WireMock is already used by tests, or added with `make lock`).

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Overselling under concurrent reservations | Conditional `UPDATE ... WHERE quantity >= q` in one transaction, SKUs in sorted order; a Testcontainers test runs 20 parallel reservations of 3 units by 20 different users against stock 40 and expects 13 successes, 7 × 409 and stock 1. |
| Double submit creates two orders | Primary key on `(user_id, key)` plus `reservation_id UNIQUE` on orders; a test fires 10 parallel `POST /api/orders` with one key and expects one order, the others replayed or 409; a different key on the same reservation gets 410 `CHECKOUT_RESERVATION_CONSUMED`. |
| Stock never comes back | Expiry sweep, release on replacement, give-back on failed payment and on recovery, all guarded by `released_at` / `restocked_at` so each happens at most once; tested with a fixed `Clock`. |
| A payment timeout holds a request for 30 s | 3 s read timeout; the mock's 30 s delay is answered `TIMED_OUT`; tested with WireMock's delay. |
| A timed-out payment was actually charged | Out of scope for the mock; noted for a real provider (reconciliation by `orderId`). |
| Stock events at peak flush caches | Stock-only `ProductUpdated` skips the webhook (Product epic); the web caches availability for seconds. |
| The demo-only outcome header changes behaviour in production | Ignored unless `PAYMENT_MOCK_OUTCOME_HEADER=true`; a test checks it is not forwarded by default. |
| Merging the Flyway strategy re-runs or loses histories | Migration test from a database migrated by the current `main` configuration, as `ProductMigrationTest`. |
| Testcontainers (Postgres, Redpanda, WireMock) cannot run in cloud sessions | They run in CI, where skipped tests fail the build; workers wait with `checks-wait`. |

## Open questions for the owner

1. **Member price at checkout.** Proposed: checkout charges the member price of the caller's tier
   (the contract example 845 500 and the Product epic say so). Alternative: the current price only,
   and the member price stays informative on the PDP.
2. **Flash sale stock exhausted.** Proposed: the line falls back to the variant price and still
   reserves regular stock (`priceChanged` signals it when the client sent the sale price).
   Alternative: 409 `CHECKOUT_OUT_OF_STOCK` when the sale stock is short.
3. **Cart after an order.** Proposed: shop-web removes the ordered lines from the cart after a
   successful order (best effort, Checkout epic in shop-web). Alternative: `core` publishes
   `order.placed` to Redpanda and `cart-service` removes the lines (a new topic, an `area:cart-service`
   task and more Phase 5 scope).
4. **Address fields.** The contract requires `ward`, `district` and `province`. Vietnam removed the
   district level in July 2025; proposed: keep the contract as is in Phase 5 (making `district`
   optional is additive and can follow). Alternative: make it optional in `1.4.0`.

## Done when

- After `make demo`, a signed-in demo user reserves cart lines, sees the shipping options and
  payment methods, and places a COD order through the gateway; every checkout and order operation
  and its documented errors has a passing contract test.
- Ten parallel `POST /api/orders` with the same `Idempotency-Key` create exactly one order; a replay
  answers the same body with `Idempotent-Replayed: true`.
- A reservation whose `expiresAt` has passed reads `EXPIRED`, cannot be ordered (410), and its stock
  is back in `product.stock` after the sweep (Testcontainers test with a fixed `Clock`).
- A failed and a timed-out mock payment end in `PAYMENT_FAILED` with payment `FAILED` / `TIMED_OUT`
  and give the stock back.
- `make demo-smoke-checkout` passes and `make lint test` is green on `main`.
