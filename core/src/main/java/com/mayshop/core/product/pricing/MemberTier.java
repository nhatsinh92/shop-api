package com.mayshop.core.product.pricing;

/** Loyalty tiers; the names match the {@code memberTier} enum of the contract. */
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
