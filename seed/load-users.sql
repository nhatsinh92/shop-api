-- Load-test users for `make demo-peak` ONLY (200 users, load-0001@example.com ... load-0200@example.com,
-- tier STANDARD). One shared demo password, documented in seed/README.md; hash is BCrypt cost 12.
-- Requires the auth schema (created by core with Flyway) and products.sql (stock rows).
-- Idempotent: existing emails are left untouched.
INSERT INTO auth.users (id, email, password_hash, display_name, member_tier)
SELECT ('10ad' || lpad(n::text, 4, '0') || '-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       'load-' || lpad(n::text, 4, '0') || '@example.com',
       '$2a$12$9EBgPftTac35axihJduO/uqW6IT.PIddkMh.lN4G9vknRGqUYT04.',
       'Load User ' || lpad(n::text, 4, '0'),
       'STANDARD'
FROM generate_series(1, 200) AS n
ON CONFLICT DO NOTHING;

-- The k6 checkout scenario places thousands of COD orders on these two high-stock SKUs; the
-- catalog stock would run out in minutes and turn the run into out-of-stock errors. `make seed`
-- restores the catalog stock.
UPDATE product.stock SET quantity = 1000000, updated_at = now()
WHERE sku IN ('SKU-BOTTLE-750', 'SKU-PAD-90');
