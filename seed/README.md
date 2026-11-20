# Seed data

`seed.sh` loads `products.sql` (ten sample products from the design canvas) into the `seed`
schema of the demo Postgres. `make demo` runs it once the database is healthy; `make seed`
runs it again. Demo users arrive with the auth epic (Phase 2).
