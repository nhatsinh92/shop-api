# gateway — agent guide

Read the root `CLAUDE.md` first. Everything in this module is security-sensitive. Since the
owner decision 2026-11-22 it merges on `agent-approved` and green CI without `human-approved`, so every rule needs a
test, including the negative cases (bad token, wrong `Origin`, limit exceeded).

## Stack

Spring Cloud Gateway server on WebFlux (`spring-cloud-starter-gateway-server-webflux`), Spring Boot
actuator. Reactive: never block (no JDBC, no `RestTemplate`, no `.block()`).

## Responsibilities (from the plan)

- Routes: `/api/cart/**` to `cart-service`, everything else under `/api/**` to `core`
  (`src/main/resources/application.yml`, property prefix `spring.cloud.gateway.server.webflux`).
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

## Commands

- `./gradlew :gateway:test`
- `./gradlew :gateway:bootRun` (expects core on :8081 and cart-service on :8082)

## May change / must not change

- May change: `gateway/**` when the issue is `area:gateway` and lists it in scope.
- Must not change: other modules, the contract, security behaviour not described in the issue.
- Stop and comment if a task would weaken JWT verification, cookie flags, CORS or rate limits.
