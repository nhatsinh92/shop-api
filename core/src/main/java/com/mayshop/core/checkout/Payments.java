package com.mayshop.core.checkout;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The payment methods of checkout and whether each can be used. Public API of the {@code checkout}
 * module. A method is unavailable only when listed in {@code CHECKOUT_DISABLED_PAYMENT_METHODS}.
 */
@Service
public class Payments {

  /** The methods of the contract, in display order. */
  public enum Method {
    COD("Thanh toán khi nhận hàng (COD)"),
    EWALLET("Ví điện tử"),
    CARD("Thẻ ngân hàng / thẻ tín dụng");

    private final String displayName;

    Method(String displayName) {
      this.displayName = displayName;
    }

    public String displayName() {
      return displayName;
    }
  }

  /** A method as shown on the checkout page. */
  public record MethodStatus(Method method, boolean available) {}

  private final Set<String> disabled;

  Payments(@Value("${checkout.payment.disabled-methods:}") String disabledMethods) {
    this.disabled =
        Arrays.stream(disabledMethods.split(","))
            .map(value -> value.trim().toUpperCase(Locale.ROOT))
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  /** Every method, in display order, with its availability. */
  public List<MethodStatus> list() {
    return Arrays.stream(Method.values())
        .map(method -> new MethodStatus(method, isAvailable(method)))
        .toList();
  }

  public boolean isAvailable(Method method) {
    return !disabled.contains(method.name());
  }
}
