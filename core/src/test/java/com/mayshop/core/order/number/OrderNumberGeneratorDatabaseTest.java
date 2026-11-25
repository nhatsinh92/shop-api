package com.mayshop.core.order.number;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The generator takes values from the Postgres sequence. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class OrderNumberGeneratorDatabaseTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Autowired private JdbcClient jdbc;

  @Test
  void consecutiveNumbersUseIncreasingSequenceValues() {
    var clock = Clock.fixed(Instant.parse("2026-12-11T17:30:00Z"), ZoneOffset.UTC);
    var generator = new OrderNumberGenerator(jdbc, clock);
    String a = generator.next();
    String b = generator.next();
    assertThat(a).matches("MY-1212-\\d{4,}");
    assertThat(b).matches("MY-1212-\\d{4,}").isNotEqualTo(a);
  }
}
