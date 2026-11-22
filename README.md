# shop-api

Part of the "Mây" e-commerce project. See `CLAUDE.md` for how this repository is built and run.

## Demo

`make demo` starts the whole stack in Docker (gateway on `:8080`) and loads the seed data. It also
generates an RSA 2048 demo signing key into `infra/.secrets/` once (git-ignored, kept on later
runs) and passes it to `core` as `AUTH_JWT_PRIVATE_KEY`. Delete `infra/.secrets/` to get a new key.

With the demo running, `make demo-smoke-auth` checks login, `GET /api/auth/me`, refresh, logout, the
revoked refresh token and the `Origin` check through the gateway (`infra/smoke-auth.sh --help`).
The demo accounts are listed in `seed/README.md`. Stop the stack with `make demo-down`.
