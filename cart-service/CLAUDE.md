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

## Commands

- `./gradlew :cart-service:test` (Testcontainers Redis; needs Docker)
- `./gradlew :cart-service:bootRun` (expects Redis from `make demo` on :6379)

## May change / must not change

- May change: `cart-service/**` for `area:cart-service` issues.
- Must not change: other deployables, the contract, the Redis key layout without an epic decision.
