package com.mayshop.core.product.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Flash-sale window boundaries and the SKUs of a window, for the sale-window job. */
public interface SaleWindowStore {

  /** Which end of a window. */
  enum Boundary {
    START,
    END
  }

  /**
   * Creates the boundary rows (not fired) of every window whose start or end is at or before {@code
   * now}; rows that exist stay as they are.
   */
  void recordPassed(Instant now);

  /**
   * Locks up to {@code limit} boundaries that passed at or before {@code now} and have not fired,
   * skipping rows another transaction holds. The locks last until the transaction ends.
   */
  List<Due> lockDue(Instant now, int limit);

  /** The SKUs of the window's items, with the data a {@code ProductUpdated} needs. */
  List<Sku> skus(UUID saleId);

  /** Marks the boundary as fired at {@code now}. */
  void markFired(UUID saleId, Boundary boundary, Instant now);

  /**
   * @param at when the boundary passed: the window's start or end
   */
  record Due(UUID saleId, Boundary boundary, Instant at) {}

  record Sku(String sku, UUID productId, String slug) {}
}
