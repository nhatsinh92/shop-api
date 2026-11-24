CREATE TABLE cart_snapshot (
    cart_key   text PRIMARY KEY,
    version    bigint      NOT NULL,
    items      jsonb       NOT NULL,
    updated_at timestamptz NOT NULL
);
