# Epic: Product

Phase 3 · repository `shop-api` · areas `contract`, `product`, `gateway`, `infra` · tracking issue:
_created with this PR_

## Goal

Serve the seeded catalog from the `product` module of `core` exactly as contract v1 specifies it
(catalog list, product detail, approximate availability, the current flash sale, the member price
and recommendations), with the `Cache-Control` and `x-cache-tags` headers the frontend caches by,
and publish a `product.updated` event whenever a product's price or stock changes. The event goes
to Redpanda (for `cart-service` in Phase 4) and, as a signed webhook, to shop-web's revalidation
route, so `revalidateTag` refreshes the cached home and product pages (shop-web Phase 3, Home + PDP).

## Scope

In scope:

- The `product` schema in Postgres (categories, products, variants, images, stock, flash sales)
  with its own Flyway location and history table, and the demo catalog in `seed/` rewritten into
  it (the `seed.product` table goes away).
- `GET /api/products`, `GET /api/products/{slug}`, `GET /api/products/{sku}/availability`,
  `GET /api/flash-sales/current`, `GET /api/products/{slug}/member-price` and
  `GET /api/recommendations`, each with a contract test, including the error responses.
- The `product.updated` event: an application event inside `core`, externalised to the Redpanda
  topic `product.updated`, and a signed revalidation webhook to shop-web.
- An internal operation that changes a product's price or stock, so the event can be triggered in
  tests and in the demo (there is no admin UI; see open question 1).
- A gateway test that `/internal/**` is never routed.
- Demo wiring: topic, webhook URL and secret in compose, `make demo-smoke-product`.

Out of scope: search, category pages and category listing endpoints; reviews and ratings; stock
reservation and the authoritative stock check (Checkout, Phase 5); `cart-service` consuming
`product.updated` (Cart, Phase 4); cache warm-up and peak tuning (Phase 6); personalised
recommendations (the MVP list is not personalised, see Decisions); any frontend work.

## Decisions

See `docs/handoff/technical-notes.md` § 3 (caching rules, tags, revalidation webhook) and § 7
(peak behaviour: price and stock cached for seconds). The rules in `core/CLAUDE.md` apply: the
`product` module owns only its own schema, its public API is the types in
`com.mayshop.core.product`, implementation lives in sub-packages.

| Topic | Decision |
| --- | --- |
| Migrations | Second module with tables, so each module gets its own Flyway instance, location and history table, as `core/CLAUDE.md` asks: `auth` keeps `db/migration/auth` and its existing history table in schema `auth` untouched (no re-run of merged migrations); `product` uses `db/migration/product` and schema `product`. Done in the first product task, with a test that both histories exist and auth's merged migrations are not re-applied. |
| Tables | `product.categories` (slug, name, parent), `product.products` (id UUID, slug unique, name, brand, description, category, created_at), `product.variants` (sku unique, product, options JSONB, price, list price), `product.images` (product, position, url, alt), `product.stock` (sku, quantity, updated_at), `product.flash_sales` (id, starts_at, ends_at) and `product.flash_sale_items` (sale, sku, sale price, sale stock, sold). Money is `bigint` VND (the contract's `Money.amount` is an integer in the minor unit; VND has none). |
| Summary SKU | A product's `ProductSummary.sku` is its first variant (lowest position); `price` and `listPrice` come from that variant. `listPrice` is omitted unless it is higher than `price`, as the contract says. Badge `DISCOUNT` when `listPrice` is present. |
| Seed | `seed/products.sql` loads categories, the ten products of the design canvas (names, brands, prices as on the artboards, the ANC Pro with three colour variants `Đen nhám`, `Trắng sứ`, `Xanh đêm`), placeholder image URLs, stock, and one flash sale whose window is computed at load time (`starts_at = now() - 1 hour`, `ends_at = now() + 2 hours`) so `make demo` always has a running sale. It runs after `core` is healthy (Flyway has created the tables), like `users.sql`. |
| Pagination | `listProducts` orders by `created_at DESC, id DESC`; the cursor is base64url JSON of the last item's `(created_at, id)`. A cursor that does not decode → 400 `PAGINATION_CURSOR_INVALID`; an unknown category slug → 200 with an empty page (filters do not 404). |
| Cache headers | Exactly the contract's: list and detail `public, max-age=120, stale-while-revalidate=600`; availability `public, max-age=5, stale-while-revalidate=10`; flash sale `public, max-age=60, stale-while-revalidate=120`; member price `private, no-store`; recommendations `private`. Tags: list `products`, detail and availability `product-{id}`, flash sale `flash-sale`. Errors carry `Cache-Control: no-store`. |
| Approximate stock | `OUT_OF_STOCK` at 0, `LOW` at 1–50 (threshold configurable, `PRODUCT_LOW_STOCK_THRESHOLD`), else `IN_STOCK`. `approxQuantity` only when `LOW`, rounded down to a multiple of 10 with a minimum of 1 (design: "còn khoảng 40"). Read from Postgres; peak caching sits in shop-web and the CDN, warm-up is Phase 6. |
| Flash sale | "Current" is the sale whose window contains now; none → 404 `FLASH_SALE_NONE`. While a sale runs, an item's `price`/`listPrice` stay the regular card prices and `salePrice` is the sale price; `soldPercent = floor(100 × sold / sale stock)`. During the sale, `availability.price` of a sale SKU is the sale price. |
| Member price | Tier from the access token's `tier` claim, read through the auth module's public API (no import of `auth` internals; `ModularityTests` stays green). Base price is the current availability price (the sale price during a flash sale); discounts STANDARD 0 %, SILVER 3 %, GOLD 5 %, PLATINUM 8 %, rounded down to 500 VND (design: GOLD 890.000₫ → 845.500₫). Configurable; see open question 2. No token → 401 `AUTH_UNAUTHENTICATED`. |
| Recommendations | Not personalised in the MVP (the contract keeps the shape independent of how items are chosen): with `productSlug`, other products of the same leaf category, then of the parent, newest first; without it, the current flash sale items followed by the newest products; never the product itself; `limit` as in the contract. Unknown `productSlug` → 200 with the fallback list. |
| Event | `ProductUpdated` (`eventId`, `occurredAt`, `productId`, `sku`, `slug`, `changes` ⊆ `PRICE`, `STOCK`, `DETAIL`, `tags`), published as a Spring application event after commit. `tags` always holds `product-{id}` and `products`, plus `flash-sale` when the SKU is in the current sale. Its JSON shape is part of the contract (see Contract impact). |
| Externalisation | Spring Modulith event publication registry (JDBC, its table in the `product` schema via the product Flyway location) plus `spring-modulith-events-kafka`: the event goes to topic `product.updated`, key `sku`, JSON. Incomplete publications are resubmitted on restart, so delivery is at least once and consumers deduplicate by `eventId`. Dependencies added with `make lock`. |
| Revalidation webhook | A `product` listener POSTs `{eventId, occurredAt, tags}` to `WEB_REVALIDATE_URL` with header `X-Shop-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<body>" with REVALIDATE_SECRET>`, timeout 2 s, 3 attempts with backoff, then a warning log and the counter `product.revalidate.failed`. No URL configured → the listener is off (tests and local runs). The cache lifetimes on the web side bound staleness when a webhook is lost. |
| Triggering changes | `PATCH /internal/products/{sku}` (`x-internal: true`, like the JWKS endpoint) with an optional `price`, `listPrice` and `stock`; it requires `X-Internal-Token` equal to `INTERNAL_API_TOKEN` (no token configured → the operation answers 404) and is never routed by the gateway. Used by tests and `make demo-smoke-product`. See open question 1. |
| Sensitive paths | The member-price task reads the token claim through the auth public API but does not change `core/.../auth/**`. No task touches a protected path. |

## Contract impact

Every public product operation is already in `core.yaml` (1.1.0). Additive changes, released as
`1.2.0` in the first task:

- `ProductUpdated` schema, and the revalidation call described as an OpenAPI 3.1 `webhooks` entry
  `productRevalidate` (body `{eventId, occurredAt, tags}`, header `X-Shop-Signature`, 204 / 401
  responses), so shop-web types its route handler from the package.
- `PATCH /internal/products/{sku}` (`x-internal: true`, `security: []` plus the `X-Internal-Token`
  header parameter, problem responses 400 / 404 / 422), if open question 1 keeps it.
- Any new error code the above needs goes into `x-error-codes`.

shop-web pins `1.2.0` in its Home + PDP epic. No breaking change.

## Expected tasks (the planner details them after approval)

1. [contract] `ProductUpdated` schema, `productRevalidate` webhook, internal product update
   operation; release `1.2.0` (S).
2. [product] Per-module Flyway, the `product` schema and tables, the seed rewritten into them (M).
3. [product] `GET /api/products` with cursor pagination and category filter, and
   `GET /api/products/{slug}`, with cache headers, tags and contract tests (M).
4. [product] `GET /api/products/{sku}/availability` with approximate stock levels (S).
5. [product] `GET /api/flash-sales/current` with the sale window, sale price and `soldPercent` (S).
6. [product] `GET /api/products/{slug}/member-price` with tier discounts from the token (S).
7. [product] `GET /api/recommendations` with the non-personalised selection rules (S).
8. [product] `PATCH /internal/products/{sku}`, the `ProductUpdated` application event and its
   externalisation to Redpanda through the publication registry (M).
9. [product] Signed revalidation webhook to shop-web with retries and the failure counter (S).
10. [gateway] Test (and fix if needed) that `/internal/**` is never routed (S).
11. [infra] Demo wiring: topic creation, `WEB_REVALIDATE_URL`, generated `REVALIDATE_SECRET` and
    `INTERNAL_API_TOKEN` in the git-ignored `infra/.secrets/`, seed after `core`,
    `make demo-smoke-product` (list → detail → availability → internal change → event on the topic) (S).

Dependencies: 1 and 2 first; 3, 4, 5 after 2; 6 after 4; 7 after 3; 8 after 1, 4 and 5; 9 after 8;
10 after 1; 11 after 5, 8 and 9. Tasks 3–7 can run in any order once their dependencies are
merged. Tasks 2 and 8 change dependencies or Flyway (`make lock`), none touches a protected path.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Splitting Flyway re-runs or loses the auth history | Task 2 keeps the auth location, schema and history table name; a Testcontainers test starts from a database migrated by the current `main` configuration and checks nothing is re-applied. |
| A lost webhook leaves the web cache stale | At-least-once publication registry, three attempts, failure counter; web cache lifetimes (seconds for price and stock, minutes for the flash sale, hours for product info) cap the staleness. |
| A forged webhook flushes the web cache at peak | HMAC signature with a timestamp (the web rejects older than 5 minutes); secret only in environment. |
| The internal operation is reachable from outside | Not routed by the gateway (task 10 tests it), off without `INTERNAL_API_TOKEN`, token compared in constant time. |
| Member price drifts from the price the user pays at checkout | One rule in the `product` public API that Checkout (Phase 5) reuses; tier discounts are configuration. |
| Rate limits on `core` routes (20 rps per user or IP) hit client-side reads | Home and PDP data is fetched by the Next.js server over the internal network, not through the gateway; only the client fallbacks (member price) go through it. Revisited in Phase 6. |
| Testcontainers (Postgres, Redpanda) cannot run in cloud sessions | They run in CI, where skipped tests fail the build; workers wait with `checks-wait`. |

## Done when

- After `make demo`, the six product operations serve the seeded catalog through the gateway, and
  every one of them (and its documented errors) has a passing contract test.
- A Testcontainers test changes a product's price through the internal operation and sees exactly
  one `product.updated` record on the topic (key = SKU, body matching `ProductUpdated`) and one
  signed webhook call on a stub web server with tags `product-{id}` and `products`.
- `GET /api/flash-sales/current` answers 404 `FLASH_SALE_NONE` outside a sale window, and a sale
  SKU's availability price equals its sale price inside one.
- `make demo-smoke-product` passes against `make demo`; `make lint test` is green on `main`.

## Open questions for the owner

1. **Triggering `product.updated` without an admin UI:** the internal `PATCH /internal/products/{sku}`
   operation with a token (proposed), or only a test-scoped publisher (no contract change, but the
   demo cannot show a live revalidation)?
2. **Member price tiers:** STANDARD 0 %, SILVER 3 %, GOLD 5 %, PLATINUM 8 % of the current price,
   rounded down to 500 VND (GOLD matches the artboard). Confirm or give the numbers.
3. **Recommendations:** non-personalised rules for the MVP as above (the artboard's "Vì bạn đã xem …"
   line has no field in the contract; shop-web's epic proposes dropping that line).
