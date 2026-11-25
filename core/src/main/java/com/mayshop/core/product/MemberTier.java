package com.mayshop.core.product;

/**
 * Loyalty tier of the caller, as the {@code tier} claim of the access token names it. Public API of
 * the module: Checkout passes it to {@link StockHolds#hold}. The names match the {@code memberTier}
 * enum of the contract.
 */
public enum MemberTier {
  STANDARD,
  SILVER,
  GOLD,
  PLATINUM;

  /** The tier named by a token claim; a missing or unknown value is {@link #STANDARD}. */
  public static MemberTier fromClaim(Object claim) {
    if (claim instanceof String name) {
      for (MemberTier tier : values()) {
        if (tier.name().equals(name)) {
          return tier;
        }
      }
    }
    return STANDARD;
  }
}
