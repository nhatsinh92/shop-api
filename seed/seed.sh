#!/bin/sh
# Loads the sample catalog. Runs inside the `seed` container of infra/docker-compose.yml,
# or on the host with PG* environment variables set. Idempotent: safe to run repeatedly.
set -eu
psql -v ON_ERROR_STOP=1 -q -f /seed/products.sql
echo "seed: $(psql -tAc 'select count(*) from seed.product') products loaded"
