# gateway — agent guide

Read the root `CLAUDE.md` first. Everything in this module is security-sensitive. Since the
owner decision 2026-11-22 it merges on `agent-approved` and green CI without `human-approved`, so every rule needs a
test, including the negative cases (bad token, wrong `Origin`, limit exceeded).

## Stack

Spring Cloud Gateway server on WebFlux (`spring-cloud-starter-gateway-server-webflux`), Spring Boot
actuator. Reactive: never block (no JDBC, no `RestTemplate`, no `.block()`).

## Responsibilities (from the plan)

- Routes, in order (`src/main/resources/application.yml`, property prefix
  `spring.cloud.gateway.server.webflux`): `cart-read` (`GET`/`HEAD` `/api/cart/**`), `cart-write`
  (other methods on `/api/cart/**`), `order-write` (`POST /api/orders`), `reservation-write`
  (`POST /api/checkout/reservations`), then `core` (`/api/**`). Cart routes use `CART_SERVICE_URL`,
  the others `CORE_URL`.
- Timeouts: each route has `response-timeout` metadata in ms (`GATEWAY_CART_READ_TIMEOUT` 300,
  `GATEWAY_CART_WRITE_TIMEOUT` 2000, `GATEWAY_ORDER_TIMEOUT` 5000, `GATEWAY_RESERVATION_TIMEOUT`
  2000, `GATEWAY_CORE_TIMEOUT` 1000); connect timeout `GATEWAY_CONNECT_TIMEOUT` 200. No route has a
  retry filter: never replay a write at the edge.
- Circuit breakers (`com.mayshop.gateway.fallback`): the `Breaker` route filter (Spring Cloud Gateway's
  `CircuitBreaker`, skipped for `/api/auth/**`) wraps `cart-read`, `cart-write` and `core`;
  `order-write`, `reservation-write` and `/api/auth/**` have none. Settings live under
  `resilience4j.circuitbreaker` in `application.yml` (window 20, minimum 10 calls, 50 % failure and
  slow-call rates, 10 s open, 5 half-open calls; `GATEWAY_BREAKER_WINDOW|MIN_CALLS|FAILURE_RATE|
  SLOW_RATE|OPEN_SECONDS|HALF_OPEN_CALLS`); slow = the route's timeout. The Resilience4j time limiter
  is disabled so the route `response-timeout` is the only bound. A timeout, connect error or
  upstream 5xx (`statusCodes` 500, 502, 503, 504) counts; 4xx never does.
- No bulkhead: `spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled` is `false`
  (`GATEWAY_BREAKER_BULKHEAD_ENABLED`). `resilience4j-bulkhead` is on the classpath, and Spring Cloud
  CircuitBreaker would otherwise put a 25-call semaphore bulkhead on every breaker route; a call over
  it answers 500 and counts as a breaker failure, so concurrency alone opened the cart breakers at
  peak (#195). The gateway is reactive (a waiting call holds no thread); timeouts, breakers and rate
  limits protect the upstreams. `BreakerConcurrencyTest` covers it.
- Fallback: `FallbackHandler` answers failed breaker calls 503 `SERVICE_UNAVAILABLE` (`Retry-After: 2`,
  `Cache-Control: no-store`) and counts `gateway.fallback{route, reason=timeout|circuit_open|
  upstream_error}`. No retries, no `.block()`.
- Metrics: `/actuator/prometheus` is served on `MANAGEMENT_PORT` (default 9080) only; the app port
  exposes `health` and `info`. Never expose the management port publicly.
- Phase 2: read the access token from the httpOnly cookie, verify it with the JWKS from `core`
  (cached), forward it as `Authorization: Bearer`; Redis-backed rate limiting per user and per IP;
  CORS only for the web origin; `Origin` check on state-changing requests.
- No business logic and no data access beyond Redis for rate limits and the optional denylist.

## Access-token handling (`com.mayshop.gateway.auth`)

- `AccessCookieFilter` (global filter): removes any client `Authorization`, verifies the
  `access_token` cookie and forwards it as `Bearer`; an invalid cookie is removed from `Cookie`, counted
  (`gateway.auth.token.invalid`, tag `reason`) and logged (never the token). The gateway never answers
  401. `/api/auth/login|refresh|logout` keep their cookies untouched.
- `AccessTokenVerifier` uses the reactive `spring-security-oauth2-jose` decoder; `JwksKeySource` fetches
  the JWKS lazily, caches it 5 minutes and refetches an unknown `kid` at most once per 30 seconds.
- `ProblemErrorHandler` turns every gateway-made error into `application/problem+json` with `code`
  and `traceId`.
- Settings: `AUTH_JWT_ISSUER`, `AUTH_JWT_AUDIENCE` (same defaults as core) and `CORE_URL`.

## Rate limits (`com.mayshop.gateway.ratelimit`)

- `RateLimitFilter` (global filter, right after `AccessCookieFilter`): `POST /api/auth/login` is limited
  per normalised email (`GATEWAY_LOGIN_PER_EMAIL_PER_MINUTE`, 5) and per client IP
  (`GATEWAY_LOGIN_PER_IP_PER_MINUTE`, 60); `POST /api/auth/refresh` per SHA-256 of the `refresh_token`
  cookie (`GATEWAY_REFRESH_PER_SESSION_PER_MINUTE`, 10), unlimited without the cookie; other `/api/**`
  routes use a token bucket (`GATEWAY_CORE_RATE` 20/s, `GATEWAY_CORE_BURST` 40) keyed by the verified
  `sub`, else the client IP. `/api/cart/**` is never limited.
- Client IP (`ClientIpResolver`): the connection's remote address, unless it is inside
  `GATEWAY_TRUSTED_PROXIES` (comma-separated IPv4/IPv6 CIDRs, default empty, an invalid entry fails
  startup). Then it is the right-most `X-Forwarded-For` entry that is not itself trusted (entries a
  client prepends sit left of the proxy chain and are ignored); all entries trusted: the left-most;
  a missing or unparsable header: the remote address. Empty list = `X-Forwarded-For` ignored.
  It keys the per-IP login limit and the IP fallback of the core bucket. Production: set it to the
  ingress CIDR only (never `0.0.0.0/0`), and make the ingress append to, not trust, client
  `X-Forwarded-For`. The demo compose file trusts the private Docker ranges.
- `LOGGING_LEVEL_COM_MAYSHOP_GATEWAY_RATELIMIT=DEBUG` logs `Client IP <ip> for <method> <path>` for
  rate-limited routes (never at INFO).
- `RedisRateLimiter` runs Lua scripts (`resources/ratelimit/`). It **fails open** with a WARN when Redis
  is unreachable; Redis health is excluded from `/actuator/health` for that reason.
- Denials are 429 `RATE_LIMITED` problems with `Retry-After` (`ProblemResponses`). Settings:
  `REDIS_HOST`, `REDIS_PORT`.

## CORS and Origin check (`com.mayshop.gateway.origin`)

- `OriginFilter` is a `WebFilter` at highest precedence, so it runs before every gateway filter
  (rejected requests consume no rate-limit tokens and never reach an upstream).
- CORS: only `WEB_ORIGIN` (default `http://localhost:3000`, property `gateway.web-origin`) gets
  `Access-Control-Allow-Origin` and `Allow-Credentials: true`; never a wildcard. Preflights answer 204
  in the gateway; other origins get no CORS headers.
- `POST`, `PUT`, `PATCH`, `DELETE` get 403 `AUTH_FORBIDDEN` when `Origin` is present and not exactly
  `WEB_ORIGIN` (including `null`), or absent with `Sec-Fetch-Site: cross-site`. No `Origin` and no
  `Sec-Fetch-Site` (server-side callers such as shop-web `proxy.ts`) passes. `GET`, `HEAD`, `OPTIONS`
  are never rejected.

## Commands

- `./gradlew :gateway:test`
- `./gradlew :gateway:bootRun` (expects core on :8081 and cart-service on :8082)

## May change / must not change

- May change: `gateway/**` when the issue is `area:gateway` and lists it in scope.
- Must not change: other modules, the contract, security behaviour not described in the issue.
- Stop and comment if a task would weaken JWT verification, cookie flags, CORS or rate limits.
