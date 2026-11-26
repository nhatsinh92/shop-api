-- Which flash-sale window boundaries (start or end) already published their price events.
-- A row is created by the sale-window job the first time it sees a boundary that has passed.
CREATE TABLE sale_window_boundary (
  sale_id  uuid NOT NULL REFERENCES flash_sales (id) ON DELETE CASCADE,
  boundary text NOT NULL CHECK (boundary IN ('START', 'END')),
  fired    boolean NOT NULL DEFAULT false,
  fired_at timestamptz,
  PRIMARY KEY (sale_id, boundary),
  CONSTRAINT sale_window_boundary_fired_at_check CHECK (fired = (fired_at IS NOT NULL))
);

-- The job looks only at boundaries that have not fired.
CREATE INDEX sale_window_boundary_pending_idx ON sale_window_boundary (sale_id) WHERE NOT fired;
