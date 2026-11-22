# shop-api roadmap

Derived from the approved build plan (`docs/handoff/shop-build-plan.md`, "Backend plan"). The
owner approves this roadmap and each epic; the planner turns approved epics into task issues.
Java 21, Spring Boot 4.0 (see "Deviations"), Gradle.

| Phase | Epic | Deliverables | Done when | Status |
| --- | --- | --- | --- | --- |
| 0 | Bootstrap | Gradle multi-project, CLAUDE.md per module, CI + merge-gate, docker-compose, Makefile, branch protection, picker scripts and role prompts | `make demo` starts all containers on a clean machine; CI green on an empty module | Done (#1) |
| 1 | [Contract v1](epics/contract-v1.md) | OpenAPI for auth, products, cart, checkout, orders; shared error schema; idempotency header; package release | `@shop/api-contract@1.0.0` released; contract lint passes | Done (#2) |
| 2 | [Auth + gateway](epics/auth-gateway.md) | Login, refresh with rotation and grace period, logout, JWKS endpoint; gateway JWT verification, cookie → Bearer, routes, Redis rate limit | Expired token refresh works; revoked refresh token rejected; gateway rejects bad JWT | Done (#42) |
| 3 | Product | Catalog, product detail, flash-sale list, approximate stock; `product.updated` events | Seeded catalog served; event emitted on change (feeds FE `revalidateTag`) | Not started |
| 4 | Cart | Redis hash per user, add / update / remove with atomic ops, guest cart + merge on login, enriched response, write-behind to Postgres via Redpanda | p95 under 50 ms locally under k6; concurrency test passes | Not started |
| 5 | Checkout + order | Price and stock confirmation, stock reservation with 10-minute TTL, order placement with `Idempotency-Key`, mock payment (success / fail / timeout), COD | Double submit creates one order; expired reservation releases stock | Not started |
| 6 | Peak readiness | Circuit breakers and timeouts, cache warm-up job, feature flags, k6 scenarios, `make demo-peak` with toxiproxy | Slowed cart-service still returns within budget via fallbacks; dashboards show latency and fallback rate | Not started |

## Cross-repo dependencies

- shop-web Phase 1 (contract client) consumes the `@shop/api-contract` release from Phase 1 here.
- Each shop-web feature phase switches from MSW mocks to the real gateway once the matching
  backend phase is merged.

## Deviations from the plan, decided during bootstrap

| Topic | Plan | Now | Why |
| --- | --- | --- | --- |
| Spring Boot | Spring Boot 3 | Spring Boot 4.0.10, Spring Cloud 2025.1.5, Spring Modulith 2.0.10 | Boot 3.5 open-source support ended on 2026-06-30. 4.0 is the line Spring Cloud 2025.1 targets; its OSS support ends 2026-12-31, so an upgrade to Boot 4.2 / Spring Cloud 2026.0 is planned as an infra task once 2026.0 has a patch release. |
| Contract package | npm package on GitHub Packages | GitHub Release tarball `contract-v<version>` with `shop-api-contract-<version>.tgz`, pinned by URL in shop-web | GitHub Packages requires the scope to match the owner (`@nhatsinh92`) and a token even for public packages; the release tarball keeps the name, is public and needs only `GITHUB_TOKEN`. Approved by the owner. |

## Housekeeping tasks (infra, not tied to a phase)

- Upgrade to Spring Boot 4.2 / Spring Cloud 2026.0 / Spring Modulith 2.2 before 2026-12-31.
- New-code coverage gate (80%) in CI (today the reviewer checks it from the JaCoCo report).
