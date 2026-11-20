-- Sample catalog for the local demo. Names and prices come from the design canvas.
-- Lives in the `seed` schema so it never collides with tables owned by core modules;
-- the product module (Phase 3) imports from here or replaces this script.
CREATE SCHEMA IF NOT EXISTS seed;

CREATE TABLE IF NOT EXISTS seed.product (
  sku          text PRIMARY KEY,
  slug         text NOT NULL UNIQUE,
  name         text NOT NULL,
  price_vnd    bigint NOT NULL CHECK (price_vnd > 0),
  list_price_vnd bigint,
  stock        integer NOT NULL CHECK (stock >= 0),
  flash_sale   boolean NOT NULL DEFAULT false
);

INSERT INTO seed.product (sku, slug, name, price_vnd, list_price_vnd, stock, flash_sale) VALUES
  ('SKU-ANC-PRO',   'tai-nghe-khong-day-anc-pro',     'Tai nghe không dây chống ồn ANC Pro',     890000, 1490000, 120, true),
  ('SKU-AIRFRY-6L', 'noi-chien-khong-dau-6l',         'Nồi chiên không dầu 6L màn hình cảm ứng', 1290000, 2190000, 60, true),
  ('SKU-JACKET-WP', 'ao-khoac-gio-unisex-chong-nuoc', 'Áo khoác gió unisex chống nước',          359000,  599000, 200, true),
  ('SKU-KB-75',     'ban-phim-co-75-hot-swap',        'Bàn phím cơ 75% hot-swap',                1150000, 1590000, 45, true),
  ('SKU-BOTTLE-750','binh-giu-nhiet-inox-750ml',      'Bình giữ nhiệt inox 750ml',               189000,  290000, 300, false),
  ('SKU-MOUSE-SIL', 'chuot-khong-day-im-lang',        'Chuột không dây im lặng',                 249000,  NULL,   150, false),
  ('SKU-STAND-AL',  'gia-do-laptop-nhom',             'Giá đỡ laptop nhôm',                      319000,  NULL,   80, false),
  ('SKU-SLEEVE-14', 'tui-chong-soc-14-inch',          'Túi chống sốc 14 inch',                   199000,  NULL,   90, false),
  ('SKU-LAMP-LED',  'den-ban-led-chong-can',          'Đèn bàn LED chống cận',                   459000,  NULL,   70, false),
  ('SKU-PAD-90',    'lot-chuot-co-lon-90cm',          'Lót chuột cỡ lớn 90cm',                   129000,  NULL,   250, false)
ON CONFLICT (sku) DO UPDATE SET
  slug = EXCLUDED.slug, name = EXCLUDED.name, price_vnd = EXCLUDED.price_vnd,
  list_price_vnd = EXCLUDED.list_price_vnd, stock = EXCLUDED.stock, flash_sale = EXCLUDED.flash_sale;
