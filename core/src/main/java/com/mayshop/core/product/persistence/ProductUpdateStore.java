package com.mayshop.core.product.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Write side of the internal product update: the variant and its stock row. */
public interface ProductUpdateStore {

  /** The SKU's variant, locked until the transaction ends, or empty when there is none. */
  Optional<Variant> lockBySku(String sku);

  /**
   * Sets the price and list price (null clears it) and, when {@code stock} is not null, the
   * quantity (creating the stock row when missing).
   */
  void update(String sku, long price, Long listPrice, Integer stock);

  /** Whether the SKU is an item of the flash sale running at {@code now}. */
  boolean inCurrentFlashSale(String sku, Instant now);

  /**
   * @param listPrice null when the variant has none
   * @param stock units in stock; 0 when the SKU has no stock row
   */
  record Variant(UUID productId, String slug, long price, Long listPrice, int stock) {}
}
