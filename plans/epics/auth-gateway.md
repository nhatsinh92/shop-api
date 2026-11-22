# Epic: Auth + gateway

Phase 2 · repository `shop-api` · areas `auth`, `gateway`, `contract`, `infra` · tracking issue:
_created with this PR_

## Goal

Implement the auth endpoints of contract v1 in `core` (login, refresh with rotation and a grace
period, logout, current user, JWKS) and make the gateway the only door to the services: it reads
the access token from the httpOnly cookie, verifies it with the JWKS from `core`, forwards it as
`Authorization: Bearer`, and applies Redis rate limits, CORS and an `Origin` check. shop-web's
Layout + auth epic (shop-web Phase 2) switches its login and refresh journeys to this backend.

## Scope

In scope:

- Database migrations in `core` (first use in the project) and the `auth` module's tables: users,
  sessions, refresh tokens.
- Demo users in `seed/`, with their passwords documented in `seed/README.md` (demo only).
- RS256 signing keys, access-token issuing and `GET /.well-known/jwks.json`.
- `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/me`
  exactly as in `contracts/openapi/core.yaml` (1.0.0), each with a contract test.
- Bearer verification in `core` for operations that require auth (defence in depth behind the
  gateway).
- A contract-test helper in `core` that validates responses against the bundled spec, used by
  every endpoint task from this phase on.
- Gateway: cookie → Bearer with JWKS verification, Redis rate limits, CORS for the web origin,
  `Origin` check on state-changing requests, problem-details errors.
- Demo wiring: keys and environment in `infra/docker-compose.yml`, a scripted auth smoke check
  against `make demo`.

Out of scope: registration, password reset, social login, email verification; the instant
revocation denylist (optional in technical notes § 6; see open questions); bearer verification in
`cart-service` (Cart epic, Phase 4); guest cart merge on login (Phase 4); any frontend work.

## Decisions

See `docs/handoff/technical-notes.md` § 5 (auth flows) and § 6 (gateway). The owner decision of
2026-11-20 on shop-api#2 applies: tokens travel only in `Set-Cookie`, never in response bodies.

| Topic | Decision |
| --- | --- |
| Migrations | Flyway (`spring-boot-starter-flyway`) in `core`, one schema per module (`auth` now). Scripts live in `core/src/main/resources/db/migration/<module>/`; each module owns only its own schema. |
| Users | `auth.users` (id UUID, email unique, case-insensitive, password hash, display name, member tier). Passwords hashed with BCrypt (cost 12) from `spring-security-crypto`. |
| Sessions | One `auth.sessions` row per login; refresh tokens belong to a session (token family). Refresh tokens are 256-bit random opaque strings; only their SHA-256 hash is stored. |
| Access token | RS256 JWT, lifetime 10 minutes (`Max-Age=600`, as in the contract example); claims `sub` (user id), `sid` (session id), `tier`, `iat`, `exp`, `iss`, `aud`, header `kid`. The `access_token` cookie's `Max-Age` equals the token lifetime, so browsers drop expired tokens themselves. |
| Refresh token | Lifetime 14 days (`Max-Age=1209600`, as in the contract example), cookie `Path=/`. Rotated on every use. |
| Grace period | 10 seconds, configurable. On rotation `core` writes `auth:grace:{oldHash}` → session id to Redis with that TTL; presenting the old token inside the window issues a fresh pair for the same session, so parallel tabs and parallel SSR refreshes all succeed. |
| Reuse detection | A rotated token presented after the grace period revokes the whole session and returns 401 `AUTH_REFRESH_INVALID`. |
| Logout | Revokes the session (all its refresh tokens) and clears the three cookies; succeeds without a valid token, as the contract says. Access tokens already issued stay valid until they expire (at most 10 minutes). |
| Signing keys | PEM private key from `AUTH_JWT_PRIVATE_KEY` (and optional `AUTH_JWT_PREVIOUS_PUBLIC_KEY` for rotation, published in the JWKS next to the current key). Without it (local runs, tests) `core` generates an ephemeral key at startup and logs a warning. `make demo` generates a demo key once into the git-ignored `infra/.secrets/` (openssl) and passes it to `core`; no private key is committed (gitleaks would fail it anyway). |
| Cookie flags | `HttpOnly; Secure; SameSite=Lax; Path=/` for both tokens; `logged_in=1` without `HttpOnly`. `Secure` cookies work on `http://localhost`, which the demo uses. |
| Bearer in core | `spring-boot-starter-oauth2-resource-server` with the local public keys (no network call); `GET /api/auth/me` accepts `cookieAuth` or `bearerAuth` as the contract lists. |
| Gateway verification | `spring-security-oauth2-jose` reactive decoder with the JWKS URI of `core` (`CORE_URL/.well-known/jwks.json`), cached for 5 minutes (`Cache-Control: max-age=300`) and refreshed on an unknown `kid`. |
| Gateway token rules | The gateway removes any `Authorization` header sent by the browser and builds it only from the `access_token` cookie. No cookie → forwarded without `Authorization` (services decide). A cookie with a bad signature, wrong issuer or audience, or an expired token → 401 `AUTH_UNAUTHENTICATED` (problem details), which triggers the browser's single-flight refresh. `/api/auth/login`, `/api/auth/refresh` and `/api/auth/logout` are exempt so an expired session can always be refreshed or cleared. `/.well-known/**` is not routed. |
| Rate limits | Spring Cloud Gateway `RequestRateLimiter` with the Redis token bucket. Key: `sub` when a valid token is present, else client IP. Proposed defaults (environment variables, tuned in Phase 6): `/api/auth/login` and `/api/auth/refresh` 10 requests per minute per IP; other `/api/**` 20 requests per second, burst 40. Exceeded → 429 `RATE_LIMITED` problem with `Retry-After`. |
| CORS and CSRF | CORS allows only `WEB_ORIGIN` (default `http://localhost:3000`) with credentials. State-changing methods (`POST`, `PUT`, `PATCH`, `DELETE`) are rejected with 403 `AUTH_FORBIDDEN` when `Origin` is present and not `WEB_ORIGIN`, or absent while `Sec-Fetch-Site` is `cross-site`. Together with `SameSite=Lax` this is the CSRF protection. |
| Errors | Every error the gateway itself produces is `application/problem+json` with a `code` from `errors.yaml` and a `traceId`. |
| Contract tests | A test helper in `core` that loads the bundled `contracts/dist/core.yaml` and validates MockMvc responses (status, headers, body). Library: `swagger-request-validator-mockmvc`, if its OpenAPI 3.1 support covers our spec (checked in its task); fallback: `networknt` JSON Schema validation of the response schemas. |
| Sensitive paths | `gateway/**` and `core/src/main/java/com/mayshop/core/auth/**` are sensitive: every PR of this epic except the contract and infra tasks needs the owner's `human-approved` label. |

## Contract impact

`/api/auth/*` and the JWKS endpoint are already specified in 1.0.0; no change is needed for them.
The gateway adds responses that the spec does not document yet:

- 401 `AUTH_UNAUTHENTICATED` on public operations when an invalid or expired access cookie is sent;
- 403 `AUTH_FORBIDDEN` on state-changing operations for a failed `Origin` check;
- 429 `RATE_LIMITED` on the cart operations in `cart.yaml` (none document it today).

These are additive (minor version, `1.1.0`) and form the first task, which runs before the
gateway tasks. If the owner prefers the gateway to forward requests with an invalid cookie
anonymously instead of rejecting them (open question 1), the 401 part is dropped.

## Expected tasks (the planner details them after approval)

1. [contract] Document gateway-level 401 / 403 / 429 responses, release `1.1.0` (S).
2. [auth] Contract-test helper in `core` validating responses against the bundled spec (S).
3. [auth] Flyway, `auth` schema (users, sessions, refresh tokens), demo users in `seed/` (M).
4. [auth] Signing keys, access-token issuing and `GET /.well-known/jwks.json` (S).
5. [auth] `POST /api/auth/login` with BCrypt, session cookies, and `GET /api/auth/me` with
   bearer/cookie verification (M).
6. [auth] `POST /api/auth/refresh` with rotation, Redis grace period and reuse detection (M).
7. [auth] `POST /api/auth/logout` with session revocation (S).
8. [gateway] Cookie → Bearer with cached JWKS verification, token rules and problem errors (M).
9. [gateway] Redis rate limits per user and per IP with 429 problems (S).
10. [gateway] CORS for the web origin and the `Origin` check (S).
11. [infra] Demo wiring: generated demo key and environment in compose, seed after `core` is healthy,
    `make demo-smoke-auth` (login → me → refresh → logout through the gateway) (S).

Dependencies: 2 and 3 first; 4 after 3; 5 after 2 and 4; 6 and 7 after 5; 8 after 1 and 4;
9 and 10 after 8; 11 after 6, 7 and 8. Tasks 8–10 and the core tasks can run in parallel once
their dependencies are merged. Task 2 changes a dependency (`make lock`), task 3 adds Flyway;
neither touches protected paths. Task 11 does not touch protected paths either (`infra/`,
`seed/`, `Makefile`).

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Every auth and gateway PR waits for the owner (`human-approved`) | Tasks are small and ordered so that non-sensitive tasks (contract, contract-test helper, infra) run while sensitive PRs wait. |
| Grace period lets a stolen refresh token be used once more | The window is 10 seconds and only for the token just rotated; reuse after it revokes the session. |
| Access tokens stay valid after logout for up to 10 minutes | Accepted for the MVP; the optional Redis denylist (open question 2) closes the gap. |
| JWKS fetch at gateway startup races `core` startup | The decoder fetches lazily and retries; compose already starts the gateway after `core` is healthy. |
| Testcontainers tests cannot run in cloud sessions (no Docker) | They run in CI, where skipped tests fail the build; workers wait with `checks-wait`. |
| OpenAPI 3.1 support of the contract-test library is incomplete | Checked first in task 2, with the JSON Schema fallback decided above. |
| Rate limits break the k6 runs in Phase 6 | Limits come from environment variables; the peak profile sets its own. |

## Done when

- A Testcontainers test logs in, waits past the access-token lifetime (shortened by configuration
  in the test), refreshes, and calls `GET /api/auth/me` successfully.
- A revoked refresh token (after logout, and a rotated token reused after the grace period) is
  rejected with 401 `AUTH_REFRESH_INVALID`.
- The gateway returns 401 `AUTH_UNAUTHENTICATED` for a token with a bad signature, an unknown
  `kid` or an expired `exp`, and forwards a valid one as `Authorization: Bearer` (gateway tests
  with a stubbed JWKS).
- `make demo-smoke-auth` passes against `make demo`; every auth operation has a contract test;
  `make lint test` is green on `main`.

## Open questions for the owner

1. **Invalid access cookie on public routes:** the gateway rejects it with 401 (proposed, so the
   browser refreshes), or strips it and forwards the request anonymously (no contract change for
   public operations, but expired sessions are noticed later)?
2. **Instant revocation:** add the optional Redis session denylist checked by the gateway in this
   phase, or leave it for Phase 6 (proposed: leave it out)?
3. **Lifetimes and limits:** confirm access 10 minutes, refresh 14 days, grace 10 seconds and the
   proposed rate-limit defaults.
4. **Demo key:** `make demo` generates the signing key into the git-ignored `infra/.secrets/`
   (proposed, keeps keys out of the public repo), or should every `make demo` run use an
   ephemeral key generated by `core` (sessions then end on each restart)?
