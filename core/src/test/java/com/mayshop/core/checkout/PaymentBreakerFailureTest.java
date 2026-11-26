package com.mayshop.core.checkout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.mayshop.core.checkout.Payments.Method;
import com.mayshop.core.checkout.Payments.Outcome;
import com.mayshop.core.flags.FeatureFlags;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Timeouts and connect errors count as failures of breaker {@code payment}. */
@SpringBootTest(
    properties = {
      "checkout.payment.timeout=200ms",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
@org.springframework.context.annotation.Import({
  CheckoutSupport.JwtConfiguration.class,
  PaymentBreakerFailureTest.Doubles.class
})
class PaymentBreakerFailureTest {

  private static final WireMockServer GATEWAY =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    GATEWAY.start();
  }

  @DynamicPropertySource
  static void gatewayUrl(DynamicPropertyRegistry registry) {
    registry.add("checkout.payment.url", () -> "http://localhost:" + GATEWAY.port());
  }

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class Doubles {

    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    InMemoryReservationStore inMemoryReservationStore() {
      return new InMemoryReservationStore();
    }

    @org.springframework.context.annotation.Bean
    org.springframework.jdbc.core.simple.JdbcClient jdbcClient() {
      return org.mockito.Mockito.mock(org.springframework.jdbc.core.simple.JdbcClient.class);
    }
  }

  @Autowired private Payments payments;
  @Autowired private CircuitBreakerRegistry registry;
  @Autowired private CircuitBreakerFactory<?, ?> factory;
  @Autowired private MeterRegistry meters;
  @MockitoBean private com.mayshop.core.product.StockHolds holds;
  @MockitoBean private FeatureFlags flags;

  @AfterAll
  static void stop() {
    GATEWAY.stop();
  }

  @BeforeEach
  void reset() {
    GATEWAY.resetAll();
    registry.circuitBreaker("payment").reset();
    when(flags.isEnabled(any())).thenReturn(true);
  }

  @Test
  void tenTimeoutsOpenTheBreaker() {
    GATEWAY.stubFor(post("/payments").willReturn(aResponse().withStatus(504).withFixedDelay(600)));

    Outcome first = payments.charge(UUID.randomUUID(), 450, Method.CARD, null);
    IntStream.range(0, 9).forEach(i -> payments.charge(UUID.randomUUID(), 450, Method.CARD, null));

    assertThat(first).isEqualTo(Outcome.TIMED_OUT);
    assertThat(registry.circuitBreaker("payment").getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void tenConnectErrorsOpenTheBreaker() {
    Payments unreachable =
        new Payments(
            "",
            "http://localhost:1",
            Duration.ofSeconds(1),
            false,
            meters,
            flags,
            factory,
            registry);

    Outcome first = unreachable.charge(UUID.randomUUID(), 450, Method.CARD, null);
    IntStream.range(0, 9)
        .forEach(i -> unreachable.charge(UUID.randomUUID(), 450, Method.CARD, null));

    assertThat(first).isEqualTo(Outcome.FAILED);
    assertThat(registry.circuitBreaker("payment").getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }
}
