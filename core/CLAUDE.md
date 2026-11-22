# core — agent guide

Read the root `CLAUDE.md` first.

## Stack

Spring Boot (Web MVC, JDBC) with Spring Modulith, Postgres. Events go through Spring application
events inside core and Redpanda (Kafka API) to other deployables once an epic introduces them.

## Application modules

| Module | Package | Owns |
| --- | --- | --- |
| `auth` | `com.mayshop.core.auth` | login, refresh tokens with rotation and grace period, logout, JWKS |
| `product` | `com.mayshop.core.product` | catalog, product detail, flash sale, approximate stock, `product.updated` |
| `order` | `com.mayshop.core.order` | orders, idempotent placement |
| `checkout` | `com.mayshop.core.checkout` | price and stock confirmation, reservations (10 min TTL), payment |

Rules:

- A module's public API is the types in its root package. Put implementation in sub-packages
  (`internal`, `web`, `persistence`); other modules must not import them.
- Cross-module calls go through the public API or `ApplicationEventPublisher` events. No shared
  tables between modules; each module owns its own tables.
- `ModularityTests` (Spring Modulith `verify()`) and `ArchitectureTest` (ArchUnit) must stay green.
  Add new architecture rules as new tests; never relax existing ones.
- `auth` is security-sensitive: PRs touching `core/src/main/java/com/mayshop/core/auth/**` merge on
  `agent-approved` and green CI (owner decision 2026-11-22), so every rule needs a test, negative cases included.

## Commands

- `./gradlew :core:test` (Testcontainers Postgres; needs Docker)
- `./gradlew :core:bootRun` (expects Postgres from `make demo` on :5432)

## May change / must not change

- May change: the module named by the issue's `area:*` label, plus its tests.
- Must not change: other modules' internals, `ModularityTests`, `ArchitectureTest`, the contract.
- Schema changes (when an epic introduces migrations) belong to the owning module only.
