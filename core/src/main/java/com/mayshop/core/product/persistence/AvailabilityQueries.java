package com.mayshop.core.product.persistence;

import com.mayshop.core.product.PriceRule.SalePrice;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of availability: price and stock of one SKU. */
public interface AvailabilityQueries {

  /** The SKU's data, or empty when no variant has this SKU. */
  Optional<AvailabilityRow> findBySku(String sku);

  /**
   * @param quantity units in stock; 0 when the SKU has no stock row
   * @param sales every flash sale price of the SKU, whatever its window
   */
  record AvailabilityRow(
      UUID productId, String sku, long variantPrice, int quantity, List<SalePrice> sales) {}
}
