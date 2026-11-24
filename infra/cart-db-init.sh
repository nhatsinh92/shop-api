#!/bin/sh
# Creates the role `cart` and the database `cart` owned by it if they are missing. Safe to run on
# a fresh and on an existing pgdata volume. Local demo only: the password is not a secret.
set -eu

psql -v ON_ERROR_STOP=1 <<'SQL'
SELECT 'CREATE ROLE cart LOGIN PASSWORD ''cart'''
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cart')\gexec

SELECT 'CREATE DATABASE cart OWNER cart'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'cart')\gexec
SQL
echo "cart database ready"
