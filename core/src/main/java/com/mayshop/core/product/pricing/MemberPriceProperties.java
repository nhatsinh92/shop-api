package com.mayshop.core.product.pricing;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code product.member-price.*}: the discount in percent per tier. */
@Component
@ConfigurationProperties(prefix = "product.member-price")
public class MemberPriceProperties {

  private Map<MemberTier, Integer> discounts = new EnumMap<>(MemberTier.class);

  public Map<MemberTier, Integer> getDiscounts() {
    return discounts;
  }

  public void setDiscounts(Map<MemberTier, Integer> discounts) {
    this.discounts = discounts;
  }

  /** The rule for the configured discounts. */
  public MemberPriceRule rule() {
    return new MemberPriceRule(discounts);
  }
}
