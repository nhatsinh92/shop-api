package com.mayshop.cart.product;

import java.util.Map;
import java.util.Set;

/** The outcome of a lookup: known products by SKU, and the SKUs that no longer exist. */
public record ProductLookupResult(Map<String, ProductInfo> known, Set<String> unknown) {

  public ProductLookupResult {
    known = Map.copyOf(known);
    unknown = Set.copyOf(unknown);
  }
}
