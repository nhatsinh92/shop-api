package com.mayshop.core.product;

import java.time.Instant;
import java.util.Collection;

/**
 * The price a SKU is sold at right now: its variant price, or the sale price while the SKU is in a
 * flash sale whose window contains the moment. Public so member price and Checkout reuse the same
 * rule instead of re-deriving it.
 */
public final class PriceRule {

  private PriceRule() {}

  /** A flash sale price of one SKU together with the window of its sale. */
  public record SalePrice(long amount, Instant startsAt, Instant endsAt) {

    /** The window is half open: it contains its start and not its end. */
    public boolean contains(Instant now) {
      return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }
  }

  /**
   * @param variantPrice the regular price of the variant, in VND
   * @param sales the flash sale prices of the SKU, in any window; empty when it is in none
   * @param now the moment to price for
   * @return the lowest sale price whose window contains {@code now}, else {@code variantPrice}
   */
  public static long currentPrice(long variantPrice, Collection<SalePrice> sales, Instant now) {
    return sales.stream()
        .filter(sale -> sale.contains(now))
        .mapToLong(SalePrice::amount)
        .min()
        .orElse(variantPrice);
  }
}
