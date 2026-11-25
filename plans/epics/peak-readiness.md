# Epic: Peak readiness

Phase 6 · repository `shop-api` · areas `contract`, `gateway`, `cart-service`, `product`,
`checkout`, `flags` (new), `infra` · tracking issue: _created with this PR_

## Goal

Keep the 12.12 hot paths answering within their budgets when a dependency is slow or down, and make
that visible: every outbound call has a timeout and a circuit breaker with a fallback, the caches
that matter at peak are warm when a flash-sale window opens, optional features can be switched off
at runtime by feature flags, and `make demo-peak` runs k6 scenarios against the stack while
toxiproxy slows `cart-service`, with Prometheus and Grafana showing latency, fallback rate and cache
hit rate. Plan § Backend plan, Phase 6: "Slowed cart-service still returns within budget via
fallbacks; dashboards show latency and fallback rate." shop-web's Peak readiness epic (Phase 6)
reads the flags and adds the `cart_count` hint cookie, Lighthouse budgets and load-test journeys.

## Scope

In scope:

- An additive contract release `1.5.0` (see Contract impact).
- Gateway: per-route response timeouts and Resilience4j circuit breakers on the `cart-service` and
  `core` routes, answering 503 `SERVICE_UNAVAILABLE` problems as the fallback; trusted proxies for
  the client IP (`X-Forwarded-For`, deferred from Phase 2, `gateway/CLAUDE.md`).
- `cart-service`: a circuit breaker on the product lookup with a stale-on-error product cache, a
  flash-sale cache warm-up, the cleanup of expired guest snapshots (deferred in the Cart epic) and a
  restore job from `cart_snapshot` (Cart epic, open question 1, planned with its proposed answer).
- `core`: a new `flags` module (`GET /api/flags`, runtime switches in Redis); recommendations
  honour their flag; a flash-sale window job that publishes price events at window start and end
  (cache warm-up for shop-web and `cart-service`); a circuit breaker on the payment client that
  marks online payment unavailable while open.
- Metrics: a Prometheus endpoint in the three apps (behind the internal token), Prometheus and
  Grafana dashboards in the peak profile.
- Load: load users for the peak profile, k6 scenarios (browse, cart, checkout) with a toxiproxy
  phase, wired into `make demo-peak`, and a nightly workflow that opens an issue on regression.

Out of scope: a waiting room at checkout (open question 2); the Redis session denylist for instant
revocation (open question 3); autoscaling and pre-scaling (no deployment target beyond the demo);
real CDN configuration; a shared Next.js cache handler for several web replicas (shop-web epic, open
question); any frontend work; Spring Boot 4.2 upgrade (housekeeping task in the roadmap).

## Decisions

See `docs/handoff/technical-notes.md` § 6 (gateway) and § 7 (peak-day behaviour: every hole has a
150–300 ms budget and a fallback, serve the last good value when the backend errors, feature flags
switch optional holes off, rate limits at checkout and never on the cart; watch fallback rate and
cache hit rate). `core/CLAUDE.md`, `gateway/CLAUDE.md` and `cart-service/CLAUDE.md` apply; the
gateway stays reactive (never block).

| Topic | Decision |
| --- | --- |
| Library | Resilience4j: `spring-cloud-starter-circuitbreaker-reactor-resilience4j` in `gateway` (Spring Cloud Gateway's `CircuitBreaker` filter), `spring-cloud-starter-circuitbreaker-resilience4j` in `core` and `cart-service`, versions from the Spring Cloud BOM, added with `make lock`. Defaults for every breaker: count-based window of 20 calls, minimum 10 calls, open at 50 % failures or 50 % slow calls (slow = the call's timeout), 10 s open, 5 calls half-open. Each is configured under `resilience4j.circuitbreaker.instances.<name>` with environment overrides. Breaker state and calls are exported as Micrometer metrics (`resilience4j_circuitbreaker_*`). |
| Gateway timeouts | Per-route `response-timeout` metadata: `GET`/`HEAD` on `/api/cart/**` 300 ms (`GATEWAY_CART_READ_TIMEOUT`), cart writes 2 s (`GATEWAY_CART_WRITE_TIMEOUT`), `POST /api/orders` 5 s (above the 3 s payment timeout, `GATEWAY_ORDER_TIMEOUT`), every other `/api/**` 1 s (`GATEWAY_CORE_TIMEOUT`); connect timeout 200 ms. This needs the cart route split into a read and a write route (same URI). |
| Gateway breakers | Breakers `cart-read`, `cart-write` and `core` (one per upstream class; `POST /api/orders` and `/api/auth/**` are not behind the `core` breaker, so a slow catalog never blocks login or a placed order). A timeout, a connect error or an upstream 5xx counts as a failure; 4xx never does. The fallback is a gateway handler answering 503 `SERVICE_UNAVAILABLE` (`application/problem+json`, `code`, `traceId`, `Retry-After: 2`, `Cache-Control: no-store`), the answer shop-web already handles (saved cart and stale banner). Counter `gateway.fallback{route, reason=timeout\|circuit_open\|upstream_error}`. No retries in the gateway (a cart add is not idempotent). |
| Writes that time out | A cart write that times out may still be applied by `cart-service`. Accepted: the client rolls back its optimistic update and refetches, so it converges on the next read; the risk is in the table below. |
| Trusted proxies | `GATEWAY_TRUSTED_PROXIES` (CIDR list, default empty = today's behaviour). Only when the connection's remote address is in the list, the client IP is the right-most `X-Forwarded-For` entry that is not itself a trusted proxy; otherwise the header is ignored. Used by the per-IP login limit and the IP fallback key. Security-sensitive (reviewer checklist), every rule with a negative test. |
| Product lookup breaker | `cart-service` wraps `CoreProductClient` in breaker `product-lookup` (the existing 150 ms timeout stays). The product cache keeps each entry fresh for 60 s (today) and usable as stale for 10 min (`PRODUCT_LOOKUP_STALE_TTL`): a lookup that fails, times out or finds the breaker open serves stale entries; only SKUs with no entry at all lead to today's 503. Counters `cart.product_cache{result=hit\|stale\|miss}` and `cart.product_lookup.failed{reason}`. `product.updated` still evicts the entry (fresh and stale). |
| Cart warm-up | On startup (after readiness) and every 60 s (`CART_WARMUP_INTERVAL`), `cart-service` reads the current flash sale from `core` (`GET /api/flash-sales/current` over the internal network) and looks up its SKUs, so the hottest SKUs are always cached; a failed warm-up is logged and counted (`cart.warmup.failed`), never fatal. |
| Snapshot cleanup | A daily job (`CART_SNAPSHOT_CLEANUP_CRON`, default 03:30 `Asia/Ho_Chi_Minh`) deletes `cart_snapshot` rows of guest keys whose `updated_at` is older than the guest cart TTL, in batches of 1 000; user rows are kept. |
| Cart restore | `make cart-restore` runs `cart-service` with `--cart.restore=true`: for every `cart_snapshot` row whose Redis key is missing it writes the hash back (with the stored version and the guest TTL for guest keys), never overwriting an existing key, then exits. Manual, for a lost Redis; not on any request path. |
| Flags module | New Spring Modulith module `com.mayshop.core.flags` (label `area:flags`, created with the tasks): public API `FeatureFlags.isEnabled(Flag)` with `enum Flag { RECOMMENDATIONS, MEMBER_PRICE, ONLINE_PAYMENT }`. Values: Redis hash `flags` (field = flag name in kebab case, value `on`/`off`), read through a 2 s in-process cache; missing field or Redis down → the default from `FLAGS_DEFAULTS` (all on). `GET /api/flags` is public (no token), answers `Cache-Control: public, max-age=5`, and has a contract test. Switching is an operator action, `make flag NAME=recommendations VALUE=off` (`redis-cli HSET`), so there is no admin endpoint to secure. `product` and `checkout` depend on `flags`, never the reverse. |
| Recommendations flag | `RECOMMENDATIONS` off → `GET /api/recommendations` answers 200 with `items: []` without querying Postgres. Member price stays served by `core`; shop-web stops asking for it when the flag is off. |
| Flash-sale window job | In `product`: every 15 s (`PRODUCT_SALE_WINDOW_INTERVAL`) a job finds sale windows whose `startsAt` or `endsAt` passed since its last run (stored per window, so each boundary fires once even with several core instances, `FOR UPDATE SKIP LOCKED`) and publishes `ProductUpdated` with `changes = [PRICE]` for each SKU in the window. The existing machinery then revalidates shop-web (tags `flash-sale`, `product-{id}`) and evicts `cart-service`'s cached prices, so the first request after the boundary sees the sale price (Product epic risk "a flash sale starts with no event"). |
| Payment breaker | `checkout`'s `Payments` client gets breaker `payment`; timeouts, connect errors and 5xx count, a 402 decline does not. While open, `charge` returns `FAILED` without calling the gateway (`checkout.payment.outcome{outcome=FAILED, reason=circuit_open}`), and `GET /api/checkout/payment-methods` reports `EWALLET` and `CARD` with `available: false`, as it does when `ONLINE_PAYMENT` is off or the method is in `CHECKOUT_DISABLED_PAYMENT_METHODS`; COD is always available. Checkout Phase 5 decision "health-based availability belongs with the circuit breaker in Phase 6". |
| Metrics endpoint | `micrometer-registry-prometheus` in all three apps; `/actuator/prometheus` is exposed on the app port and answers only with `Authorization: Bearer <INTERNAL_API_TOKEN>` (constant-time compare; anything else 404, as the internal product endpoints); health and info stay public. The gateway gets `INTERNAL_API_TOKEN` in compose. Histograms (`percentiles-histogram`) for `http.server.requests`, `spring.cloud.gateway.requests`, `cart.product_lookup` and `checkout.payment`. |
| Dashboards | Peak profile adds Prometheus (scrape every 5 s, bearer token from the demo secret file) and provisions Grafana with a Prometheus datasource and one dashboard `Mây peak`: gateway p50/p95/p99 and rate per route, 5xx and 429 rate, fallback rate per route and reason, breaker states, cart product cache hit / stale / miss ratio, product lookup latency, payment outcomes, reservations expired. Dashboard JSON lives in `infra/peak/grafana/`. |
| Peak profile limits | `docker-compose.peak.yml` sets `GATEWAY_CORE_RATE`, `GATEWAY_CORE_BURST` and `GATEWAY_LOGIN_PER_IP_PER_MINUTE` high enough that k6 from one IP is not limited; per-user checkout limits stay as in production, so the checkout scenario uses many users. Defaults outside the peak profile do not change. |
| Load users | `seed/load-users.sql`, loaded only by `make demo-peak`: 200 users `load-0001@example.com` … with one shared demo password documented in `seed/README.md`, tier `STANDARD`. |
| k6 scenarios | `infra/peak/k6/peak.js` replaces the placeholder `smoke.js` in `make demo-peak` (the existing `cart.js` and `make demo-k6-cart` stay): `browse` (products, product detail, flash sale, availability), `cart` (the Phase 4 mix), `checkout` (reserve two lines, COD order with an `Idempotency-Key`, one replay), 3 minutes in total. Between 60 s and 120 s the script adds a 1 000 ms latency toxic to `cart-service` through the toxiproxy API and removes it afterwards. Thresholds: cart requests p95 < 350 ms over the whole run (300 ms timeout plus gateway overhead), every cart response during the toxic phase is 2xx or 503 `SERVICE_UNAVAILABLE`, browse and checkout error rate < 1 % and p95 < 300 ms; `gateway_fallback_total` grows during the toxic phase (read from Prometheus by a final check in the script or the Makefile). |
| Nightly run | `.github/workflows/peak-nightly.yml` runs `make demo-peak` on a schedule (and `workflow_dispatch`), uploads the k6 summary, and on a threshold failure opens or updates an issue labelled `bug` + `area:infra` with the summary. Protected path: the task carries `infra-change` and needs the owner's `human-approved`. |
| Sensitive paths | The gateway tasks change routing, rate-limit keys and a new IP rule (security checklist, not protected). The metrics endpoint tasks add an authenticated actuator endpoint (security checklist). Only the nightly workflow touches a protected path. |

## Contract impact

Additive changes, released as `1.5.0` in the first task (minor bump; `oasdiff` reports no breaking
change):

- `GET /api/flags` (tag `flags`, no security): 200 `FeatureFlags` = `{ recommendations,
  memberPrice, onlinePayment }`, each a required boolean; documented that clients treat unknown
  properties as unknown flags and ignore them, and that the answer may be up to 5 s old.
- 503 `SERVICE_UNAVAILABLE` (problem details, `Retry-After`) on every `core` operation under
  `/api/**` that the gateway's `core` breaker covers, described as the gateway fallback; `cart.yaml`
  already documents 503 on every cart operation, its description gains "also answered by the
  gateway when `cart-service` is slow or unavailable".
- `GET /api/recommendations`: description says the list is empty when the `recommendations` flag is
  off. `GET /api/checkout/payment-methods`: description says online methods are unavailable while
  the payment provider is failing or the `onlinePayment` flag is off.

shop-web pins `1.5.0` in its Peak readiness epic.

## Expected tasks (the planner details them after approval)

1. [contract] `GET /api/flags`, gateway 503 fallbacks on core operations, description updates;
   release `1.5.0` (S).
2. [gateway] Prometheus endpoint behind the internal token; cart read / write routes; per-route
   timeouts; `cart-read`, `cart-write` and `core` breakers with the 503 fallback and the
   `gateway.fallback` counter (M).
3. [gateway] Trusted proxies for the client IP, with negative tests (S).
4. [cart-service] Prometheus endpoint; `product-lookup` breaker and the stale-on-error product
   cache with its counters (M).
5. [cart-service] Flash-sale cache warm-up on startup and every 60 s (S).
6. [cart-service] Guest snapshot cleanup job and `--cart.restore` restore mode (M).
7. [flags] `flags` module: `FeatureFlags`, Redis values with defaults, `GET /api/flags` with its
   contract test, Modulith verification (M).
8. [infra] Prometheus endpoint in `core` behind the internal token, histograms, in the application
   root package (S).
9. [product] Recommendations honour `RECOMMENDATIONS`; the flash-sale window job publishing price
   events at window boundaries, tested with a fixed `Clock` (M).
10. [checkout] `payment` breaker, `FAILED` without a call while open, payment-method availability
    from the breaker and `ONLINE_PAYMENT` (M).
11. [infra] Peak profile: Prometheus, Grafana datasource and the `Mây peak` dashboard, peak rate
    limits, `make flag`, `make cart-restore`, the gateway's `INTERNAL_API_TOKEN` (M).
12. [infra] Load users, `peak.js` with the toxiproxy phase and thresholds, wired into
    `make demo-peak` (M).
13. [infra] Nightly `peak-nightly.yml` that opens an issue on regression (S, `infra-change`).

Dependencies: 1 first; 2 and 7 after 1; 3, 4 and 8 independent; 5 after 4; 6 independent; 9 and 10
after 7; 11 after 2, 4, 8 and 10; 12 after 3, 9 and 11; 13 after 12. Tasks 2 and 3 both touch the
gateway and run one after the other (3 after 2 if both are ready).

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| A timed-out cart write is applied anyway, and the client shows it as failed | The write timeout is 2 s (only reads have 300 ms); shop-web refetches after a failed mutation; the k6 toxic phase checks the cart converges after the toxic is removed. |
| The breaker opens on a few slow calls and sheds healthy traffic | Minimum 10 calls in a 20-call window, 50 % threshold, half-open after 10 s; all values are environment variables tuned with the dashboard. |
| Stale prices in the cart after a sale ends | Stale entries are served only while `core` fails; `product.updated` evicts them; the window job fires at every boundary; checkout confirms the real price (`priceChanged`). |
| The metrics endpoint leaks internals | Bearer internal token, 404 otherwise, tested; the token is a demo secret file, never in the repository. |
| Trusting `X-Forwarded-For` lets clients pick their IP and dodge login limits | Only from configured proxy CIDRs, right-most untrusted entry, default empty; negative tests with a spoofed header from an untrusted address. |
| The window job fires twice with several core instances | Per-window boundary rows locked with `SKIP LOCKED`; a test runs two jobs concurrently and expects one event per SKU and boundary. |
| Flags in Redis are lost with Redis | Defaults from `FLAGS_DEFAULTS` apply (all on); a flag switched off must be switched off again after a Redis loss (documented in `make flag`). |
| k6 thresholds are flaky on CI runners | Thresholds are relative to the budgets with headroom; the nightly run opens an issue instead of failing a PR. |
| Testcontainers and Docker-based checks cannot run in cloud sessions | They run in CI, where skipped tests fail the build; workers wait with `checks-wait`. |

## Open questions for the owner

1. **Breakers on `core` routes.** Proposed: one `core` breaker for catalog, product and checkout
   reads and writes except `POST /api/orders` and `/api/auth/**`. Alternative: no breaker on `core`
   routes, timeouts only (the plan names "slowed cart-service"; `core` is called by shop-web's
   server, mostly over the internal network).
2. **Waiting room.** Technical notes § 7 place a waiting room at checkout; the plan's Phase 6 row
   does not list it. Proposed: out of scope; the per-user checkout limits stay the only admission
   control. Alternative: a gateway admission limit on `POST /api/checkout/reservations` (global
   concurrent-reservation budget, 429 with `Retry-After`) as one more gateway task.
3. **Instant revocation.** The Auth epic left the Redis denylist "to revisit in Phase 6". Proposed:
   still out of scope (access tokens live up to 10 minutes after logout). Alternative: a gateway
   task checking a Redis denylist of session ids (about 1 ms per request).
4. **Cart restore.** Planned as a manual `make cart-restore` (task 6) from the Cart epic's accepted
   proposal. Alternative: drop it and keep `cart_snapshot` as a durable record only.

## Done when

- With `make demo-peak`, while toxiproxy adds 1 000 ms to `cart-service`, cart requests through the
  gateway answer within 350 ms at p95 (2xx or 503 `SERVICE_UNAVAILABLE`), browse and checkout stay
  under 1 % errors, and the k6 run passes its thresholds.
- The Grafana dashboard `Mây peak` shows gateway latency per route, fallback rate and cart product
  cache hit ratio from Prometheus during that run.
- `make flag NAME=recommendations VALUE=off` makes `GET /api/flags` report it within 5 s and
  `GET /api/recommendations` answer an empty list; `make flag NAME=online-payment VALUE=off` marks
  `EWALLET` and `CARD` unavailable.
- With the mock payment gateway down, after the breaker opens online orders fail fast and the
  payment methods report online payment unavailable; COD orders still succeed.
- At a flash-sale window start, `product.updated` price events are published once per SKU (fixed
  `Clock` test).
- `make lint test` is green on `main`, and the nightly peak workflow has run once successfully.
