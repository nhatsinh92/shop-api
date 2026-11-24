# cart-service — agent guide

Read the root `CLAUDE.md` first.

## Stack

Spring Boot (Web MVC) with Spring Data Redis. This is the hot path on sale day: every request must
be cheap, bounded and observable.

## Responsibilities (from the plan, Phase 4)

- Cart as a Redis hash per user (`cart:{userId}`), add / update / remove with atomic operations.
- Guest cart and merge on login; enriched response (name, image, cached price).
- Write-behind to Postgres via Redpanda; listens to `product.updated` for price changes.
- Target: p95 under 50 ms locally under k6.

## Rules

- Never read the core database. Prices come from `core` over HTTP (cached) or from events.
- No blocking calls without a timeout; every outbound call has a timeout and a fallback.
- Concurrency correctness is tested (two concurrent requests keep the quantity correct).

## Identity (package `security/`, reviewed with the security checklist)

- `Authorization: Bearer` is verified against `${CORE_URL}/.well-known/jwks.json` (cached, connect and
  read timeouts), RS256 only, issuer `AUTH_JWT_ISSUER`, audience `AUTH_JWT_AUDIENCE`. Failure: 401
  `AUTH_UNAUTHENTICATED`.
- `CallerIdentityFilter` runs on `/api/cart/**` after the Bearer filter: a verified token is a
  `User` (id = `sub`); no Bearer with `logged_in=1` is 401 (the client refreshes); otherwise a
  `Guest`. Controllers take a `CallerIdentity` parameter. Do not make the filter a bean (it would
  also run before Spring Security).
- `guest_cart` must match `^[A-Za-z0-9_-]{22}$`; anything else counts as no cookie (never 400) and
  increments `cart.guest_cookie.invalid`. Reads never create an id; write endpoints call
  `GuestCartCookies.newId()` and `setCookie(...)`.
- Errors are `application/problem+json` with `code`, `traceId` and `Cache-Control: no-store`
  (`web/Problems`, `CartExceptionHandler`); Redis connection failures are 503 `SERVICE_UNAVAILABLE`.
- Contract tests use `ContractValidator.cart()` (spec bundled by the `bundleContract` Gradle task).

## Commands

- `./gradlew :cart-service:test` (Testcontainers Redis; needs Docker)
- `./gradlew :cart-service:bootRun` (expects Redis from `make demo` on :6379)

## May change / must not change

- May change: `cart-service/**` for `area:cart-service` issues.
- Must not change: other deployables, the contract, the Redis key layout without an epic decision.
