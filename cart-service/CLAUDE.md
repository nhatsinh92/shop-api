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

## Write-behind (package `writebehind/`)

- Every successful add, update, remove and merge sends a `CartUpdated` JSON record to topic
  `cart.updated` (key = cart key, value `{cartKey, version, updatedAt, items: [{sku, quantity,
  addedAt}]}`) from the snapshot the Lua script returned; merge also sends an empty snapshot for the
  guest key. `CartUpdatedPublisher` hands the send to one bounded worker thread (`acks=all`), so the
  request never waits on the broker; a failed or dropped send is logged and counted as
  `cart.write_behind.failed`.
- `writebehind/store/` is the only package that may use JDBC or Flyway (ArchUnit rule
  `WriteBehindBoundariesTest`). The consumer (group `cart-writer`, `earliest`, endless retry with
  exponential backoff) upserts into `cart_snapshot(cart_key, version, items jsonb, updated_at)` only
  when the version is higher, and deletes the row for an empty cart. A late record that carries a
  lower version for an emptied cart can recreate its row; the next write or the Phase 6 cleanup
  repairs it.
- Flyway (`db/migration`) runs from `CartSnapshotStore` on the first write, not at start-up, so
  Postgres being down never stops the service. Database settings: `CART_DB_URL`, `CART_DB_USER`,
  `CART_DB_PASSWORD`. Health: neither Postgres nor Kafka is part of readiness.

## Snapshot cleanup and restore (packages `writebehind/store/` and `restore/`)

- `SnapshotCleanupJob` runs daily (`CART_SNAPSHOT_CLEANUP_CRON`, default `0 30 3 * * *`, zone
  `Asia/Ho_Chi_Minh`). It deletes `cart_snapshot` rows of guest keys (`cart:guest:*`) whose
  `updated_at` is older than the guest cart TTL (`CartKey.GUEST_TTL`), 1 000 rows per statement until
  a batch comes back short. User rows are never deleted. A failed run is logged; the next night
  retries.
- **Restore is a manual action for a lost Redis**, never part of a request path or start-up:
  `make cart-restore` (demo stack running) starts a one-off `cart-service` container with
  `--cart.restore=true`. `CartServiceApplication.main` sees the flag and runs `CartRestoreApplication`
  instead: a small context with Redis and Postgres only (no HTTP, no scheduler, no Kafka listener)
  that exits 0 when the restore finished and 1 when Redis or Postgres is unreachable.
- For every `cart_snapshot` row whose Redis key is missing, `restore.lua` writes the hash back with
  the stored `version`, `updatedAt` and lines in one script call, and never overwrites an existing
  key. Guest keys get the remaining TTL (`updated_at` + guest TTL - now); guest rows already past it
  are skipped (the cleanup job would delete them). User keys get the user TTL (`CartKey.USER_TTL`).
  The log line and result count restored, skipped-existing, skipped-expired and malformed rows.
- `CartRestoreApplication` has no stereotype annotation on purpose: the component scan of the normal
  application must not pick it up.

## Commands

- `./gradlew :cart-service:test` (Testcontainers Redis; needs Docker)
- `./gradlew :cart-service:bootRun` (expects Redis from `make demo` on :6379)

## May change / must not change

- May change: `cart-service/**` for `area:cart-service` issues.
- Must not change: other deployables, the contract, the Redis key layout without an epic decision.
