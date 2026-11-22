package com.mayshop.core.product.pricing;

import java.util.Map;

/** Member price: the base price less the tier discount, rounded down to a multiple of 500 VND. */
public final class MemberPriceRule {

  static final long ROUNDING_STEP = 500;

  private final Map<MemberTier, Integer> discountPercent;

  /**
   * @param discountPercent discount in percent per tier; a tier that is not listed gets none
   */
  public MemberPriceRule(Map<MemberTier, Integer> discountPercent) {
    this.discountPercent = Map.copyOf(discountPercent);
  }

  /**
   * @param basePrice the current price of the SKU, in VND
   * @return the price for the tier; {@code basePrice} itself when the tier has no discount
   */
  public long priceFor(long basePrice, MemberTier tier) {
    int percent = discountPercent.getOrDefault(tier, 0);
    if (percent <= 0) {
      return basePrice;
    }
    long discounted = basePrice * (100 - percent) / 100;
    return discounted / ROUNDING_STEP * ROUNDING_STEP;
  }
}
