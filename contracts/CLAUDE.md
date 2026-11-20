# contracts — agent guide

Read the root `CLAUDE.md` first. The OpenAPI files here are the source of truth for every API.
Services implement them; `shop-web` generates its types and mocks from the released package.

## Files

- `openapi/core.yaml`: auth, products, orders, checkout (served by `core` behind the gateway).
- `openapi/cart.yaml`: cart (served by `cart-service`).
- `openapi/errors.yaml`: shared components every spec `$ref`s: `Money`, `Sku`, `Slug`, `Uuid`,
  `CursorPage` and the `Limit` / `Cursor` parameters, the `IdempotencyKey` header parameter, the
  `Problem` schema, shared problem responses (`BadRequest`, `Unauthorized`, `Forbidden`,
  `NotFound`, `Conflict`, `UnprocessableEntity`, `TooManyRequests`, `ServiceUnavailable`,
  `InternalError`) and the error-code catalogue `x-error-codes`.
- `scripts/check-conventions.mjs`: the convention check (see Rules); `check-conventions.test.mjs`
  holds its fixture tests (`node --test`).
- `package.json`: the package `@shop/api-contract`. Its `version` is the contract version.

## Versioning and release

- Semantic versioning. Every spec's `info.version` equals `package.json` `version`
  (`node contracts/scripts/check-version.mjs` enforces it).
- Additive changes are a minor bump; breaking changes (removed or renamed fields, operations or
  enum values, new required request fields, changed types) are a major bump.
- A **major bump is a sensitive change**: `merge-gate` requires the owner's `human-approved` label.
- On `main`, the `contract-publish` workflow runs `npm pack` and creates the GitHub Release
  `contract-v<version>` with the tarball attached when that release does not exist yet.
  `shop-web` pins the tarball URL:
  `https://github.com/nhatsinh92/shop-api/releases/download/contract-v<version>/shop-api-contract-<version>.tgz`

## Rules

- Only `area:contract` issues edit these files. Other tasks open a proposal issue instead.
- Lint with `make lint-contract`. Do not add entries to `.redocly.lint-ignore.yaml` or relax
  `redocly.yaml` (protected paths).
- Every operation has an `operationId`, a tag, a `4xx` response using `Problem` and examples for
  request bodies.
- Add every new error code to `x-error-codes` in `errors.yaml` (status and meaning) in the task that
  introduces it. `Problem.code` stays a pattern-constrained string, never an enum.
- Protected operations list `cookieAuth` and `bearerAuth` as two alternative requirement objects;
  public ones set `security: []`. Specs default to both schemes (secure by default), so every public operation must opt out explicitly with `security: []`.
- `make lint-contract` runs the convention check: every operation needs an `operationId`, a tag, an
  example on JSON request bodies and 2xx JSON responses, and an explicit 4xx response (only `health` operations may use `default` instead) using
  `application/problem+json` with `Problem`, whose examples use catalogued codes.
