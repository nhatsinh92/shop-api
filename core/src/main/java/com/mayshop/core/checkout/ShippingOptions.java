package com.mayshop.core.checkout;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The shipping options of checkout, fixed by configuration (no table). Public API of the {@code
 * checkout} module: {@code order} reads the fee of the chosen option here.
 */
@Service
public class ShippingOptions {

  /** Delivery dates are computed in Vietnam local time. */
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private final Clock clock;

  ShippingOptions(ObjectProvider<Clock> clock) {
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /** An option as shown on the checkout page; {@code fee} is whole VND. */
  public record Option(String id, String name, long fee, String estimatedDelivery) {}

  /** The options in display order, with delivery estimates for today. */
  public List<Option> list() {
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    return List.of(
        new Option("STANDARD", "Giao tiêu chuẩn", 0, standardEstimate(today)),
        new Option("EXPRESS_2H", "Giao nhanh 2h", 35_000, "Nhận trong hôm nay (nội thành)"));
  }

  /** The fee in VND of the option, or empty when the id is not an option. */
  public Optional<Long> fee(String optionId) {
    return list().stream()
        .filter(option -> option.id().equals(optionId))
        .map(Option::fee)
        .findFirst();
  }

  /**
   * "Nhận {d+1}–{d+2}/{MM}"; when the two days fall in different months each carries its month:
   * "Nhận 30/11–1/12".
   */
  static String standardEstimate(LocalDate today) {
    LocalDate from = today.plusDays(1);
    LocalDate to = today.plusDays(2);
    if (from.getMonth() == to.getMonth()) {
      return String.format(
          "Nhận %d–%d/%02d", from.getDayOfMonth(), to.getDayOfMonth(), to.getMonthValue());
    }
    return String.format(
        "Nhận %d/%02d–%d/%02d",
        from.getDayOfMonth(), from.getMonthValue(), to.getDayOfMonth(), to.getMonthValue());
  }
}
