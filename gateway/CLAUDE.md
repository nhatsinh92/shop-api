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
- Client IP is the connection's remote address; `X-Forwarded-For` is not trusted (Phase 6).
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
