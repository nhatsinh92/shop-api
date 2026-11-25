package com.mayshop.core.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ShippingOptionsTest {

  private static ShippingOptions at(String instant) {
    var factory = new StaticListableBeanFactory();
    factory.addBean("clock", Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    return new ShippingOptions(factory.getBeanProvider(Clock.class));
  }

  @Test
  void optionsComeFromConfigurationWithTheStandardFeeFreeAndExpressAt35000() {
    List<ShippingOptions.Option> options = at("2026-12-12T03:00:00Z").list();
    assertThat(options)
        .extracting(
            ShippingOptions.Option::id, ShippingOptions.Option::name, ShippingOptions.Option::fee)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("STANDARD", "Giao tiêu chuẩn", 0L),
            org.assertj.core.groups.Tuple.tuple("EXPRESS_2H", "Giao nhanh 2h", 35_000L));
  }

  @Test
  void standardEstimateOn12DecemberLocalIsNhan1314Of12() {
    assertThat(at("2026-12-12T03:00:00Z").list().get(0).estimatedDelivery())
        .isEqualTo("Nhận 13–14/12");
  }

  @Test
  void estimateUsesTheVietnamDayNotTheUtcDay() {
    // 18:00 UTC on 12 December is 01:00 on 13 December in Ho Chi Minh City.
    assertThat(at("2026-12-12T18:00:00Z").list().get(0).estimatedDelivery())
        .isEqualTo("Nhận 14–15/12");
  }

  @Test
  void estimateAcrossAMonthEndNamesBothMonths() {
    // 30 November local: 1 and 2 December, same month.
    assertThat(at("2026-11-30T03:00:00Z").list().get(0).estimatedDelivery())
        .isEqualTo("Nhận 1–2/12");
    // 31 December local: 1 and 2 January of the next year, same month.
    assertThat(at("2026-12-31T03:00:00Z").list().get(0).estimatedDelivery())
        .isEqualTo("Nhận 1–2/01");
  }

  @Test
  void estimateWhenTheSecondDayCrossesTheMonth() {
    assertThat(at("2026-11-29T03:00:00Z").list().get(0).estimatedDelivery())
        .isEqualTo("Nhận 30/11–1/12");
  }

  @Test
  void expressIsDeliveredTodayInTheCity() {
    assertThat(at("2026-12-12T03:00:00Z").list().get(1).estimatedDelivery())
        .isEqualTo("Nhận trong hôm nay (nội thành)");
  }

  @Test
  void feeIsExposedPerOptionIdForTheOrderModule() {
    ShippingOptions options = at("2026-12-12T03:00:00Z");
    assertThat(options.fee("STANDARD")).contains(0L);
    assertThat(options.fee("EXPRESS_2H")).contains(35_000L);
    assertThat(options.fee("DRONE")).isEmpty();
    assertThat(options.fee(null)).isEmpty();
  }
}
