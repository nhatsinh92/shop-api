package com.mayshop.core.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.checkout.Payments.Method;
import org.junit.jupiter.api.Test;

class PaymentsTest {

  @Test
  void everyMethodIsAvailableByDefault() {
    Payments payments = new Payments("");
    assertThat(payments.list()).hasSize(3).allMatch(Payments.MethodStatus::available);
  }

  @Test
  void onlyListedMethodsAreUnavailableIgnoringCaseAndSpaces() {
    Payments payments = new Payments(" card , ewallet,");
    assertThat(payments.isAvailable(Method.CARD)).isFalse();
    assertThat(payments.isAvailable(Method.EWALLET)).isFalse();
    assertThat(payments.isAvailable(Method.COD)).isTrue();
  }
}
