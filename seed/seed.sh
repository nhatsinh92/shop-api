#!/bin/sh
# Loads the sample catalog. Runs inside the `seed` container of infra/docker-compose.yml,
# or on the host with PG* environment variables set. Idempotent: safe to run repeatedly.
set -eu
psql -v ON_ERROR_STOP=1 -q -f /seed/products.sql
echo "seed: $(psql -tAc 'select count(*) from product.products') products loaded"
psql -v ON_ERROR_STOP=1 -q -f /seed/users.sql
echo "seed: $(psql -tAc 'select count(*) from auth.users') users loaded"
# Load users for the k6 peak run, only when asked (make demo-peak sets SEED_LOAD_USERS=1).
if [ "${SEED_LOAD_USERS:-}" = "1" ]; then
  psql -v ON_ERROR_STOP=1 -q -f /seed/load-users.sql
  echo "seed: $(psql -tAc "select count(*) from auth.users where email like 'load-%@example.com'") load users loaded"
fi
