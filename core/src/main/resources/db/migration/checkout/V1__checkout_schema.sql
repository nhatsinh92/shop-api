-- Owned by the checkout module. One schema per module: nothing outside checkout touches these tables.

CREATE TABLE checkout.reservations (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  status      text        NOT NULL
    CONSTRAINT reservations_status_check CHECK (status IN ('ACTIVE', 'EXPIRED', 'CONSUMED', 'RELEASED')),
  created_at  timestamptz NOT NULL,
  expires_at  timestamptz NOT NULL,
  -- Set once, when the held stock is given back (replacement or expiry); guards against a second release.
  released_at timestamptz,
  -- Set once, when the stock is given back after a failed payment.
  restocked_at timestamptz
);

-- A user has at most one ACTIVE reservation, so repeated clicks cannot hoard stock.
CREATE UNIQUE INDEX reservations_one_active_per_user ON checkout.reservations (user_id)
  WHERE status = 'ACTIVE';

-- The expiry sweep looks for ACTIVE rows that are due.
CREATE INDEX reservations_active_expires_at ON checkout.reservations (expires_at)
  WHERE status = 'ACTIVE';

CREATE TABLE checkout.reservation_items (
  reservation_id   uuid    NOT NULL REFERENCES checkout.reservations (id) ON DELETE CASCADE,
  sku              text    NOT NULL,
  position         integer NOT NULL,
  quantity         integer NOT NULL CONSTRAINT reservation_items_quantity_check CHECK (quantity > 0),
  -- Money is bigint VND. unit_price includes the member discount, base_price does not.
  unit_price       bigint  NOT NULL,
  base_price       bigint  NOT NULL,
  price_changed    boolean NOT NULL,
  -- Whether the line took flash sale stock; releasing the line gives sale stock back only then.
  took_sale_stock  boolean NOT NULL,
  name             text    NOT NULL,
  variant_label    text,
  image_url        text    NOT NULL,
  PRIMARY KEY (reservation_id, sku)
);
