# Seed data

`seed.sh` loads `products.sql` into the `product` schema (categories, ten products of the design
canvas with their variants, images, stock and one flash sale) and then `users.sql` into
`auth.users`. Both schemas are created by Flyway when `core` starts, so `core` must have started
once first. `make demo` runs the seed once `core` is healthy; `make seed` runs it again.
Loading is idempotent (upserts). The old `seed.product` table is dropped.

The flash sale window is computed when the seed loads: it starts one hour ago and ends two hours
later. Running `make demo` again reloads the seed and starts a new two-hour sale window.

## Demo users

**For the demo only. Never use these accounts or passwords anywhere else.**

| Email | Password | Tier |
| --- | --- | --- |
| `linh@example.com` | `linh-gold-2026` | `GOLD` |
| `minh@example.com` | `minh-silver-2026` | `SILVER` |
| `an@example.com` | `an-standard-2026` | `STANDARD` (non-member) |

Passwords are stored as BCrypt hashes with cost 12.

## Loading order

`users.sql` needs `auth.users`, so the `seed` compose service waits until `core` is healthy.
`make seed` therefore works on its own: it starts `postgres` and `core` if they are not running yet.
`make demo-smoke-auth` logs in as `linh@example.com` from the table above.

`make demo-smoke-product` reads the ANC Pro (`tai-nghe-khong-day-anc-pro`, three variants, stock 40
each, flash sale price 890 000) and changes the price of `SKU-MOUSE-SIL` (not in the sale), so run `make seed` to
restore the catalog after it.

## Load users (`make demo-peak` only)

`load-users.sql` creates 200 users `load-0001@example.com` to `load-0200@example.com`, tier
`STANDARD`, all with the demo password below, for the k6 peak run (`infra/peak/k6/peak.js`).
`seed.sh` loads them only when `SEED_LOAD_USERS=1`, which `make demo-peak` sets; `make demo` and
`make seed` do not. Reloading is idempotent. The file also raises the stock of `SKU-BOTTLE-750` and
`SKU-PAD-90` to 1 000 000 for the checkout scenario (`make seed` restores the catalog stock).

**For the demo only. Never use this password anywhere else.**

Load user password: `load-test-2026`
