# Seed data

`seed.sh` loads `products.sql` (ten sample products from the design canvas) into the `seed`
schema of the demo Postgres. `make demo` runs it once the database is healthy; `make seed`
runs it again.

`seed.sh` then loads `users.sql` into `auth.users`, which `core` creates with Flyway at startup, so
`core` must have started once first. Loading is idempotent (`ON CONFLICT DO NOTHING`).

## Demo users

**For the demo only. Never use these accounts or passwords anywhere else.**

| Email | Password | Tier |
| --- | --- | --- |
| `linh@example.com` | `linh-gold-2026` | `GOLD` |
| `minh@example.com` | `minh-silver-2026` | `SILVER` |
| `an@example.com` | `an-standard-2026` | `STANDARD` (non-member) |

Passwords are stored as BCrypt hashes with cost 12.
