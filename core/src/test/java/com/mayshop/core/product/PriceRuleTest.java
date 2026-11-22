package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.product.PriceRule.SalePrice;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceRuleTest {

  private static final Instant NOW = Instant.parse("2026-12-12T10:00:00Z");

  @Test
  void priceIsTheVariantPriceWithoutASale() {
    assertThat(PriceRule.currentPrice(1_290_000, List.of(), NOW)).isEqualTo(1_290_000);
  }

  @Test
  void priceIsTheSalePriceWhileTheWindowContainsNow() {
    var around = new SalePrice(890_000, NOW.minusSeconds(3600), NOW.plusSeconds(7200));

    assertThat(PriceRule.currentPrice(1_290_000, List.of(around), NOW)).isEqualTo(890_000);
  }

  @Test
  void priceIsTheVariantPriceForASaleInThePast() {
    var past = new SalePrice(890_000, NOW.minusSeconds(7200), NOW.minusSeconds(3600));

    assertThat(PriceRule.currentPrice(1_290_000, List.of(past), NOW)).isEqualTo(1_290_000);
  }

  @Test
  void priceIsTheVariantPriceForASaleInTheFuture() {
    var future = new SalePrice(890_000, NOW.plusSeconds(60), NOW.plusSeconds(3600));

    assertThat(PriceRule.currentPrice(1_290_000, List.of(future), NOW)).isEqualTo(1_290_000);
  }

  @Test
  void windowContainsItsStartButNotItsEnd() {
    var sale = new SalePrice(890_000, NOW, NOW.plusSeconds(60));

    assertThat(sale.contains(NOW)).isTrue();
    assertThat(sale.contains(NOW.plusSeconds(60))).isFalse();
  }

  @Test
  void theLowestRunningSalePriceWins() {
    var a = new SalePrice(900_000, NOW.minusSeconds(60), NOW.plusSeconds(60));
    var b = new SalePrice(800_000, NOW.minusSeconds(60), NOW.plusSeconds(60));

    assertThat(PriceRule.currentPrice(1_290_000, List.of(a, b), NOW)).isEqualTo(800_000);
  }
}
