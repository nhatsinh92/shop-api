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

## Contract tests

Every endpoint has a contract test that validates the real response against the spec.
`./gradlew :core:test` bundles `contracts/openapi/core.yaml` into `contracts/dist/core.yaml` first
(task `bundleContract`, Redocly version read from the `Makefile`), so no manual step is needed.

```java
@Autowired private MockMvc mvc;

@Test
void listProductsMatchesTheContract() throws Exception {
  var response = mvc.perform(get("/api/products").accept(MediaType.APPLICATION_JSON))
      .andReturn().getResponse();
  ContractValidator.core().assertValid(response, "listProducts"); // operationId from core.yaml
}
```

- Package `com.mayshop.core.contract` (test sources). It checks the status (documented code, range
  or `default`), the `Content-Type` (for example `application/problem+json`), required headers and
  the JSON body against the schema (JSON Schema 2020-12, so OpenAPI 3.1 `type: [x, 'null']` works).
- Test the error responses of your endpoint too, each with its own `operationId` call.
- Send an `Accept: application/json` header on actuator endpoints, which otherwise answer with a
  vendor media type.
- Use `ContractValidator.validate(response, operationId)` to get the violations as a list.
- Why not `swagger-request-validator`: it loads the 3.1 bundle but ignores `type: [x, 'null']`
  (a number passed for `nextCursor`), so it cannot check our 3.1 schemas.

## May change / must not change

- May change: the module named by the issue's `area:*` label, plus its tests.
- Must not change: other modules' internals, `ModularityTests`, `ArchitectureTest`, the contract.
- Schema changes belong to the owning module only.

## Migrations

- Flyway runs at startup. One Postgres schema per module (`auth` for the auth module), and a module
  owns only its own schema. Scripts live in `src/main/resources/db/migration/<module>/` and are
  named `V<n>__<description>.sql`; never edit a migration that has been merged, add a new one.
- Flyway is configured in `application.yml` for the `auth` module today. When a second module gets
  tables, give each module its own Flyway location and schema instead of sharing one history table.
- Never store raw secrets (passwords, refresh tokens): only hashes.
