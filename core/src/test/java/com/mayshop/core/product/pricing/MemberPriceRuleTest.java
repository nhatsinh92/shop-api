package com.mayshop.core.product.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MemberPriceRuleTest {

  private final MemberPriceRule rule =
      new MemberPriceRule(
          Map.of(
              MemberTier.STANDARD, 0,
              MemberTier.SILVER, 3,
              MemberTier.GOLD, 5,
              MemberTier.PLATINUM, 8));

  @Test
  void goldOn890000Is845500() {
    assertThat(rule.priceFor(890_000, MemberTier.GOLD)).isEqualTo(845_500);
  }

  @Test
  void goldOn845500Is803000() {
    assertThat(rule.priceFor(845_500, MemberTier.GOLD)).isEqualTo(803_000);
  }

  @Test
  void standardIsUnchanged() {
    assertThat(rule.priceFor(890_000, MemberTier.STANDARD)).isEqualTo(890_000);
    assertThat(rule.priceFor(123_456, MemberTier.STANDARD)).isEqualTo(123_456);
  }

  @Test
  void platinumOn1290000Is1186500() {
    assertThat(rule.priceFor(1_290_000, MemberTier.PLATINUM)).isEqualTo(1_186_500);
  }

  @Test
  void aConfigurationOverrideChangesTheDiscount() {
    MemberPriceRule custom = new MemberPriceRule(Map.of(MemberTier.GOLD, 10));
    assertThat(custom.priceFor(890_000, MemberTier.GOLD)).isEqualTo(801_000);
    assertThat(custom.priceFor(890_000, MemberTier.PLATINUM)).isEqualTo(890_000);
  }

  @Test
  void aTierWithoutConfigurationPaysTheBasePrice() {
    assertThat(new MemberPriceRule(Map.of()).priceFor(500_000, MemberTier.GOLD)).isEqualTo(500_000);
  }

  @Test
  void tierClaimParsingFallsBackToStandard() {
    assertThat(MemberTier.fromClaim("GOLD")).isEqualTo(MemberTier.GOLD);
    assertThat(MemberTier.fromClaim("gold")).isEqualTo(MemberTier.STANDARD);
    assertThat(MemberTier.fromClaim(null)).isEqualTo(MemberTier.STANDARD);
    assertThat(MemberTier.fromClaim(5)).isEqualTo(MemberTier.STANDARD);
  }
}
