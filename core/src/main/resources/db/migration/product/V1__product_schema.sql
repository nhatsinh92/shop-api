-- Product module schema: catalog, variants, images, stock and flash sales.
-- Money is bigint VND (the contract's Money.amount is an integer in the minor unit).

CREATE TABLE categories (
  slug        text PRIMARY KEY,
  name        text NOT NULL,
  parent_slug text REFERENCES categories (slug)
);

CREATE TABLE products (
  id            uuid PRIMARY KEY,
  slug          text NOT NULL UNIQUE,
  name          text NOT NULL,
  brand         text NOT NULL,
  description   text NOT NULL DEFAULT '',
  category_slug text NOT NULL REFERENCES categories (slug),
  created_at    timestamptz NOT NULL DEFAULT now()
);

-- Keyset pagination: created_at DESC, id DESC.
CREATE INDEX products_created_at_id_idx ON products (created_at DESC, id DESC);
CREATE INDEX products_category_idx ON products (category_slug);

CREATE TABLE variants (
  sku        text PRIMARY KEY,
  product_id uuid NOT NULL REFERENCES products (id) ON DELETE CASCADE,
  -- The summary SKU of a product is its variant with the lowest position.
  position   integer NOT NULL,
  -- Array of {"name": ..., "value": ...}, as ProductVariant.options in the contract.
  options    jsonb NOT NULL DEFAULT '[]'::jsonb,
  price      bigint NOT NULL CHECK (price > 0),
  list_price bigint CHECK (list_price > 0),
  UNIQUE (product_id, position)
);

CREATE TABLE images (
  product_id uuid NOT NULL REFERENCES products (id) ON DELETE CASCADE,
  position   integer NOT NULL,
  url        text NOT NULL,
  alt        text NOT NULL,
  PRIMARY KEY (product_id, position)
);

CREATE TABLE stock (
  sku        text PRIMARY KEY REFERENCES variants (sku) ON DELETE CASCADE,
  quantity   integer NOT NULL CHECK (quantity >= 0),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE flash_sales (
  id        uuid PRIMARY KEY,
  starts_at timestamptz NOT NULL,
  ends_at   timestamptz NOT NULL,
  CONSTRAINT flash_sales_window_check CHECK (ends_at > starts_at)
);

CREATE TABLE flash_sale_items (
  sale_id    uuid NOT NULL REFERENCES flash_sales (id) ON DELETE CASCADE,
  sku        text NOT NULL REFERENCES variants (sku) ON DELETE CASCADE,
  sale_price bigint NOT NULL CHECK (sale_price > 0),
  sale_stock integer NOT NULL CHECK (sale_stock > 0),
  sold       integer NOT NULL DEFAULT 0 CHECK (sold >= 0),
  PRIMARY KEY (sale_id, sku),
  CONSTRAINT flash_sale_items_sold_within_stock_check CHECK (sold <= sale_stock)
);
