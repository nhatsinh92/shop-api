package com.mayshop.core.product.persistence;

import com.mayshop.core.product.persistence.AvailabilityQueries.AvailabilityRow;
import com.mayshop.core.product.persistence.CatalogQueries.OptionRow;
import java.util.Collection;
import java.util.List;

/** Read side of the internal batch lookup: several SKUs in a bounded number of queries. */
public interface LookupQueries {

  /** The rows of the SKUs that exist, in no particular order; unknown SKUs are absent. */
  List<LookupRow> findBySkus(Collection<String> skus);

  /**
   * @param availability price, sale prices and stock of the SKU, as the availability endpoint reads
   *     them
   * @param options the options of this variant
   * @param variantCount how many variants the product has; 1 means a single-variant product
   * @param listPrice the variant's list price, or null
   */
  record LookupRow(
      String sku,
      String slug,
      String name,
      String imageUrl,
      List<OptionRow> options,
      int variantCount,
      Long listPrice,
      AvailabilityRow availability) {}
}
