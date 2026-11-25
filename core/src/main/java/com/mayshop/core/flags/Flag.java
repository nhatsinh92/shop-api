package com.mayshop.core.flags;

import java.util.Locale;

/** The feature flags an operator can switch during a peak. */
public enum Flag {
  RECOMMENDATIONS,
  MEMBER_PRICE,
  ONLINE_PAYMENT;

  /** The flag's name in Redis and in {@code FLAGS_DEFAULTS}, for example {@code member-price}. */
  public String key() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
