package com.mayshop.core.order.number;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Order numbers: {@code MY-} + the order date as {@code MMdd} in {@code Asia/Ho_Chi_Minh} + {@code
 * -} + the next value of a Postgres sequence padded to at least 4 digits, for example {@code
 * MY-1212-0087}.
 */
@Component
public class OrderNumberGenerator {

  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMdd");

  private final JdbcClient jdbc;
  private final Clock clock;

  OrderNumberGenerator(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Takes the next sequence value and formats the number for the current instant. */
  public String next() {
    long sequence =
        jdbc.sql("select nextval('orders.order_number_seq')").query(Long.class).single();
    return format(ZonedDateTime.now(clock), sequence);
  }

  /** Formats a number for a date and a sequence value (the date is read in Ho Chi Minh time). */
  static String format(ZonedDateTime when, long sequence) {
    return "MY-"
        + DAY.format(when.withZoneSameInstant(ZONE))
        + "-"
        + String.format("%04d", sequence);
  }
}
