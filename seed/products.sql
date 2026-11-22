-- Demo catalog for `make demo`, loaded into the tables core's `product` migrations create.
-- Names and prices come from the design canvas (plans/epics/product.md, "Seed numbers").
-- Idempotent: running it again upserts the catalog and starts a new flash sale window
-- (starts_at = now() - 1 hour, ends_at = now() + 2 hours).

-- The pre-Phase 3 sample table is replaced by the product schema.
DROP TABLE IF EXISTS seed.product;
-- No CASCADE: fails loudly if someone put other objects in the schema.
DROP SCHEMA IF EXISTS seed;

INSERT INTO product.categories (slug, name, parent_slug) VALUES
  ('dien-tu', 'Điện tử', NULL),
  ('gia-dung', 'Gia dụng', NULL),
  ('thoi-trang', 'Thời trang', NULL),
  ('am-thanh', 'Âm thanh', 'dien-tu'),
  ('phu-kien-may-tinh', 'Phụ kiện máy tính', 'dien-tu')
ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, parent_slug = EXCLUDED.parent_slug;

-- created_at is spread one minute apart (first product newest) so the list order is stable.
INSERT INTO product.products (id, slug, name, brand, description, category_slug, created_at) VALUES
  ('00000000-0000-4000-8000-000000000001', 'tai-nghe-khong-day-anc-pro', 'Tai nghe không dây chống ồn ANC Pro', 'Mây Audio', 'Chống ồn chủ động đa tầng, thời lượng pin đến 40 giờ.', 'am-thanh', now() - interval '1 minutes'),
  ('00000000-0000-4000-8000-000000000002', 'noi-chien-khong-dau-6l', 'Nồi chiên không dầu 6L màn hình cảm ứng', 'Mây Home', 'Dung tích 6 lít, màn hình cảm ứng, 8 chế độ nấu cài sẵn.', 'gia-dung', now() - interval '2 minutes'),
  ('00000000-0000-4000-8000-000000000003', 'ao-khoac-gio-unisex-chong-nuoc', 'Áo khoác gió unisex chống nước', 'Mây Wear', 'Vải chống nước, nhẹ, gấp gọn bỏ túi.', 'thoi-trang', now() - interval '3 minutes'),
  ('00000000-0000-4000-8000-000000000004', 'ban-phim-co-75-hot-swap', 'Bàn phím cơ 75% hot-swap', 'Mây Gear', 'Layout 75%, switch hot-swap, kết nối ba chế độ.', 'phu-kien-may-tinh', now() - interval '4 minutes'),
  ('00000000-0000-4000-8000-000000000005', 'binh-giu-nhiet-inox-750ml', 'Bình giữ nhiệt inox 750ml', 'Mây Home', 'Inox 304, giữ nóng 12 giờ, giữ lạnh 24 giờ.', 'gia-dung', now() - interval '5 minutes'),
  ('00000000-0000-4000-8000-000000000006', 'chuot-khong-day-im-lang', 'Chuột không dây im lặng', 'Mây Gear', 'Nút bấm êm, pin dùng đến 12 tháng.', 'phu-kien-may-tinh', now() - interval '6 minutes'),
  ('00000000-0000-4000-8000-000000000007', 'gia-do-laptop-nhom', 'Giá đỡ laptop nhôm', 'Mây Gear', 'Nhôm nguyên khối, gập gọn, nhiều góc nghiêng.', 'phu-kien-may-tinh', now() - interval '7 minutes'),
  ('00000000-0000-4000-8000-000000000008', 'tui-chong-soc-14-inch', 'Túi chống sốc 14 inch', 'Mây Gear', 'Lớp đệm chống sốc, ngăn phụ cho sạc và phụ kiện.', 'phu-kien-may-tinh', now() - interval '8 minutes'),
  ('00000000-0000-4000-8000-000000000009', 'den-ban-led-chong-can', 'Đèn bàn LED chống cận', 'Mây Home', 'Ánh sáng chống chói, ba mức nhiệt màu, điều chỉnh độ sáng.', 'gia-dung', now() - interval '9 minutes'),
  ('00000000-0000-4000-8000-000000000010', 'lot-chuot-co-lon-90cm', 'Lót chuột cỡ lớn 90cm', 'Mây Gear', 'Bề mặt vải mịn, đế cao su chống trượt, dài 90 cm.', 'phu-kien-may-tinh', now() - interval '10 minutes')
ON CONFLICT (id) DO UPDATE SET
  slug = EXCLUDED.slug, name = EXCLUDED.name, brand = EXCLUDED.brand,
  description = EXCLUDED.description, category_slug = EXCLUDED.category_slug;

INSERT INTO product.variants (sku, product_id, position, options, price, list_price) VALUES
  ('SKU-ANC-PRO-BLK', '00000000-0000-4000-8000-000000000001', 1, '[{"name": "color", "value": "Đen nhám"}]'::jsonb, 1290000, 1790000),
  ('SKU-ANC-PRO-WHT', '00000000-0000-4000-8000-000000000001', 2, '[{"name": "color", "value": "Trắng sứ"}]'::jsonb, 1290000, 1790000),
  ('SKU-ANC-PRO-BLU', '00000000-0000-4000-8000-000000000001', 3, '[{"name": "color", "value": "Xanh đêm"}]'::jsonb, 1290000, 1790000),
  ('SKU-AIRFRY-6L', '00000000-0000-4000-8000-000000000002', 1, '[]'::jsonb, 1690000, 2150000),
  ('SKU-JACKET-WP', '00000000-0000-4000-8000-000000000003', 1, '[]'::jsonb, 459000, 599000),
  ('SKU-KB-75', '00000000-0000-4000-8000-000000000004', 1, '[]'::jsonb, 1390000, 1690000),
  ('SKU-BOTTLE-750', '00000000-0000-4000-8000-000000000005', 1, '[]'::jsonb, 249000, 320000),
  ('SKU-MOUSE-SIL', '00000000-0000-4000-8000-000000000006', 1, '[]'::jsonb, 249000, NULL),
  ('SKU-STAND-AL', '00000000-0000-4000-8000-000000000007', 1, '[]'::jsonb, 319000, NULL),
  ('SKU-SLEEVE-14', '00000000-0000-4000-8000-000000000008', 1, '[]'::jsonb, 199000, NULL),
  ('SKU-LAMP-LED', '00000000-0000-4000-8000-000000000009', 1, '[]'::jsonb, 459000, NULL),
  ('SKU-PAD-90', '00000000-0000-4000-8000-000000000010', 1, '[]'::jsonb, 129000, NULL)
ON CONFLICT (sku) DO UPDATE SET
  product_id = EXCLUDED.product_id, position = EXCLUDED.position, options = EXCLUDED.options,
  price = EXCLUDED.price, list_price = EXCLUDED.list_price;

-- Placeholder images: two per product.
INSERT INTO product.images (product_id, position, url, alt)
SELECT p.id, g.position, 'https://placehold.co/800x800?text=' || p.slug || '-' || g.position, p.name || ' (' || g.position || ')'
FROM product.products p CROSS JOIN (VALUES (1), (2)) AS g(position)
ON CONFLICT (product_id, position) DO UPDATE SET url = EXCLUDED.url, alt = EXCLUDED.alt;

INSERT INTO product.stock (sku, quantity) VALUES
  ('SKU-ANC-PRO-BLK', 40),
  ('SKU-ANC-PRO-WHT', 40),
  ('SKU-ANC-PRO-BLU', 40),
  ('SKU-AIRFRY-6L', 60),
  ('SKU-JACKET-WP', 200),
  ('SKU-KB-75', 45),
  ('SKU-BOTTLE-750', 300),
  ('SKU-MOUSE-SIL', 150),
  ('SKU-STAND-AL', 80),
  ('SKU-SLEEVE-14', 90),
  ('SKU-LAMP-LED', 70),
  ('SKU-PAD-90', 250)
ON CONFLICT (sku) DO UPDATE SET quantity = EXCLUDED.quantity, updated_at = now();

-- One flash sale; a sale covers whole products, so the three ANC Pro variants share their numbers.
-- Reloading replaces the sale (items go with it) and opens a new window.
DELETE FROM product.flash_sales;
INSERT INTO product.flash_sales (id, starts_at, ends_at) VALUES
  ('00000000-0000-4000-8000-0000000f1a58', now() - interval '1 hour', now() + interval '2 hours');

INSERT INTO product.flash_sale_items (sale_id, sku, sale_price, sale_stock, sold) VALUES
  ('00000000-0000-4000-8000-0000000f1a58', 'SKU-ANC-PRO-BLK', 890000, 100, 82),
  ('00000000-0000-4000-8000-0000000f1a58', 'SKU-ANC-PRO-WHT', 890000, 100, 82),
  ('00000000-0000-4000-8000-0000000f1a58', 'SKU-ANC-PRO-BLU', 890000, 100, 82),
  ('00000000-0000-4000-8000-0000000f1a58', 'SKU-AIRFRY-6L', 1290000, 100, 64),
  ('00000000-0000-4000-8000-0000000f1a58', 'SKU-JACKET-WP', 359000, 100, 47),
  ('00000000-0000-4000-8000-0000000f1a58', 'SKU-KB-75', 1150000, 100, 91),
  ('00000000-0000-4000-8000-0000000f1a58', 'SKU-BOTTLE-750', 189000, 100, 30);
