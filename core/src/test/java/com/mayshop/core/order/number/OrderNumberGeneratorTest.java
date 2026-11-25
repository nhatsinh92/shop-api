package com.mayshop.core.order.number;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** Order number format: MY-MMdd-NNNN in Asia/Ho_Chi_Minh. */
class OrderNumberGeneratorTest {

  @Test
  void formatsTheSequencePaddedToFourDigits() {
    var when = ZonedDateTime.of(2026, 12, 12, 10, 0, 0, 0, ZoneId.of("Asia/Ho_Chi_Minh"));
    assertThat(OrderNumberGenerator.format(when, 87)).isEqualTo("MY-1212-0087");
  }

  @Test
  void aUtcTimeOn1211At1730IsAlreadyThe12thInHoChiMinh() {
    var when = ZonedDateTime.of(2026, 12, 11, 17, 30, 0, 0, ZoneOffset.UTC);
    assertThat(OrderNumberGenerator.format(when, 1)).isEqualTo("MY-1212-0001");
  }

  @Test
  void aUtcTimeBefore1700IsStillTheSameDay() {
    var when = ZonedDateTime.of(2026, 12, 11, 16, 59, 0, 0, ZoneOffset.UTC);
    assertThat(OrderNumberGenerator.format(when, 5)).isEqualTo("MY-1211-0005");
  }

  @Test
  void aSequenceLongerThanFourDigitsIsNotTruncated() {
    var when = ZonedDateTime.of(2026, 12, 12, 10, 0, 0, 0, ZoneId.of("Asia/Ho_Chi_Minh"));
    assertThat(OrderNumberGenerator.format(when, 12345)).isEqualTo("MY-1212-12345");
  }
}
