package com.mayshop.core.checkout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.mayshop.core.checkout.Payments.Method;
import com.mayshop.core.checkout.Payments.Outcome;
import com.mayshop.core.contract.ContractValidator;
import com.mayshop.core.flags.FeatureFlags;
import com.mayshop.core.flags.Flag;
import com.mayshop.core.product.StockHolds;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Breaker {@code payment} of {@link Payments} against an in-process WireMock, without Docker. */
@SpringBootTest(
    properties = {
      "checkout.payment.timeout=3s",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
@AutoConfigureMockMvc
@Import({CheckoutSupport.JwtConfiguration.class, PaymentBreakerTest.Doubles.class})
class PaymentBreakerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final WireMockServer GATEWAY =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    GATEWAY.start();
  }

  @DynamicPropertySource
  static void gatewayUrl(DynamicPropertyRegistry registry) {
    registry.add("checkout.payment.url", () -> "http://localhost:" + GATEWAY.port());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Doubles {

    @Bean
    @Primary
    InMemoryReservationStore inMemoryReservationStore() {
      return new InMemoryReservationStore();
    }

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-12-12T03:00:00Z"), ZoneOffset.UTC);
    }

    @Bean
    JdbcClient jdbcClient() {
      return Mockito.mock(JdbcClient.class);
    }
  }

  @Autowired private Payments payments;
  @Autowired private CircuitBreakerRegistry registry;
  @Autowired private Resilience4JConfigurationProperties cloudProperties;
  @Autowired private MeterRegistry meters;
  @Autowired private MockMvc mvc;
  @MockitoBean private StockHolds holds;
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

  private Outcome charge() {
    return payments.charge(UUID.randomUUID(), 450, Method.CARD, null);
  }

  private void chargeTimes(int times) {
    IntStream.range(0, times).forEach(i -> charge());
  }

  private CircuitBreaker breaker() {
    return registry.circuitBreaker("payment");
  }

  private JsonNode methods() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                get("/api/checkout/payment-methods")
                    .header(
                        "Authorization",
                        "Bearer " + CheckoutSupport.token(CheckoutSupport.USER, "GOLD"))
                    .accept(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(ContractValidator.core().validate(response, "listPaymentMethods")).isEmpty();
    return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("items");
  }

  private static boolean available(JsonNode items, String id) {
    for (JsonNode item : items) {
      if (id.equals(item.get("id").asString())) {
        return item.get("available").asBoolean();
      }
    }
    throw new AssertionError("Method " + id + " is missing");
  }

  @Test
  void breakerPaymentUsesTheEpicDefaults() {
    CircuitBreakerConfig config = breaker().getCircuitBreakerConfig();

    assertThat(config.getSlidingWindowType())
        .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
    assertThat(config.getSlidingWindowSize()).isEqualTo(20);
    assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
    assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
    assertThat(config.getSlowCallRateThreshold()).isEqualTo(50f);
    assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(3));
    assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
    assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
        .isEqualTo(Duration.ofSeconds(10).toMillis());
  }

  @Test
  void theTimeLimiterNeverCutsAChargeBeforeThePaymentTimeout() {
    assertThat(cloudProperties.isDisableTimeLimiter()).isTrue();
  }

  @Test
  void aPaymentAnsweringAfterOneAndAHalfSecondsSucceeds() {
    GATEWAY.stubFor(post("/payments").willReturn(aResponse().withStatus(201).withFixedDelay(1500)));

    assertThat(charge()).isEqualTo(Outcome.SUCCEEDED);
    assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void tenDeclinesLeaveTheBreakerClosed() {
    GATEWAY.stubFor(post("/payments").willReturn(aResponse().withStatus(402)));

    chargeTimes(10);

    assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(charge()).isEqualTo(Outcome.FAILED);
    GATEWAY.verify(11, postRequestedFor(urlEqualTo("/payments")));
  }

  @Test
  void tenServerErrorsOpenTheBreaker() {
    GATEWAY.stubFor(post("/payments").willReturn(aResponse().withStatus(500)));

    chargeTimes(10);

    assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void whileTheBreakerIsOpenChargeFailsWithoutCallingTheGateway() {
    GATEWAY.stubFor(post("/payments").willReturn(aResponse().withStatus(201)));
    breaker().transitionToOpenState();

    Outcome outcome = charge();

    assertThat(outcome).isEqualTo(Outcome.FAILED);
    GATEWAY.verify(0, postRequestedFor(urlEqualTo("/payments")));
    assertThat(
            meters
                .get("checkout.payment.outcome")
                .tag("outcome", "FAILED")
                .tag("reason", "circuit_open")
                .counter()
                .count())
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void paymentMethodsAreAvailableWhileEverythingIsHealthy() throws Exception {
    JsonNode items = methods();

    assertThat(available(items, "COD")).isTrue();
    assertThat(available(items, "EWALLET")).isTrue();
    assertThat(available(items, "CARD")).isTrue();
  }

  @Test
  void onlinePaymentIsUnavailableWhileTheBreakerIsOpen() throws Exception {
    breaker().transitionToOpenState();

    JsonNode items = methods();

    assertThat(available(items, "EWALLET")).isFalse();
    assertThat(available(items, "CARD")).isFalse();
    assertThat(available(items, "COD")).isTrue();
  }

  @Test
  void onlinePaymentIsUnavailableWhileTheOnlinePaymentFlagIsOff() throws Exception {
    when(flags.isEnabled(Flag.ONLINE_PAYMENT)).thenReturn(false);

    JsonNode items = methods();

    assertThat(available(items, "EWALLET")).isFalse();
    assertThat(available(items, "CARD")).isFalse();
    assertThat(available(items, "COD")).isTrue();
  }

  @Test
  void aMethodInTheDisabledListIsUnavailableAndTheOthersStayAvailable() {
    Payments disabled =
        new Payments(
            "EWALLET",
            "http://localhost:" + GATEWAY.port(),
            Duration.ofSeconds(3),
            false,
            meters,
            flags,
            null,
            registry);

    assertThat(disabled.list())
        .extracting(Payments.MethodStatus::available)
        .containsExactly(true, false, true);
  }
}
