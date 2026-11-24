# shop-api

Part of the "Mây" e-commerce project. See `CLAUDE.md` for how this repository is built and run.

## Demo

`make demo` starts the whole stack in Docker (gateway on `:8080`) and loads the seed data. It also
generates an RSA 2048 demo signing key into `infra/.secrets/` once (git-ignored, kept on later
runs) and passes it to `core` as `AUTH_JWT_PRIVATE_KEY`. Delete `infra/.secrets/` to get a new key.

The demo also creates the `product.updated` topic and generates two random secrets into
`infra/.secrets/`: `revalidate-secret` (passed to `core` as `REVALIDATE_SECRET`) and
`internal-api-token` (`INTERNAL_API_TOKEN`). `core` sends the signed revalidation webhook to
`WEB_REVALIDATE_URL`, which defaults to `http://host.docker.internal:3000/hooks/revalidate`: shop-web
running on the host (`make demo` in shop-web, port 3000). **Give shop-web the same secret** by
starting it with `REVALIDATE_SECRET="$(cat infra/.secrets/revalidate-secret)"`. Override
`WEB_REVALIDATE_URL`, or set it to an empty value to turn the webhook off.

With the demo running, `make demo-smoke-auth` checks login, `GET /api/auth/me`, refresh, logout, the
revoked refresh token and the `Origin` check through the gateway (`infra/smoke-auth.sh --help`).
`make demo-smoke-product` checks the catalog through the gateway (list, detail, availability, flash
sale), then changes a price directly on `core` through `PATCH /internal/products/{sku}`, checks that
the `product.updated` record arrives and that the gateway does not route the internal operation
(`infra/smoke-product.sh --help`).
`make demo-smoke-cart` runs the seven cart operations through the gateway as a guest, checks the 401 for
an expired session, then logs in, merges the guest cart and checks that the `cart_snapshot` row in the
`cart` database has the version of the last write (`infra/smoke-cart.sh --help`). The `cart` database
and role are created by the one-shot `cart-db` service, on a fresh or an existing volume.
The demo accounts are listed in `seed/README.md`. Stop the stack with `make demo-down`.
