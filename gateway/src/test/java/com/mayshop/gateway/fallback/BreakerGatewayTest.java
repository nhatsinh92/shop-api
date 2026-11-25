package com.mayshop.gateway.fallback;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.InMemoryRateLimiter;
import com.mayshop.gateway.support.ProblemSchema;
import com.mayshop.gateway.support.ScriptedUpstream;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The cart-read, cart-write and core breakers and their 503 fallback. */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@AutoConfigureWebTestClient
class BreakerGatewayTest {

  private static final String WEB = "http://localhost:3000";
  private static final ScriptedUpstream CORE = new ScriptedUpstream();
  private static final ScriptedUpstream CART = new ScriptedUpstream();

  @TestConfiguration(proxyBeanMethods = false)
  static class Config {
    @Bean
    @Primary
    InMemoryRateLimiter inMemoryRateLimiter() {
      return new InMemoryRateLimiter();
    }
  }

  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) {
    registry.add("CORE_URL", CORE::url);
    registry.add("CART_SERVICE_URL", CART::url);
    registry.add("GATEWAY_CART_READ_TIMEOUT", () -> "300");
    registry.add("GATEWAY_CORE_TIMEOUT", () -> "400");
  }

  @AfterAll
  static void stop() {
    CORE.close();
    CART.close();
  }

  @Autowired private WebTestClient client;
  @Autowired private InMemoryRateLimiter limiter;
  @Autowired private CircuitBreakerRegistry breakers;
  @Autowired private MeterRegistry meters;
  @LocalManagementPort private int managementPort;

  @BeforeEach
  void reset() {
    limiter.reset();
    CORE.reset();
    CART.reset();
    for (String name : new String[] {"cart-read", "cart-write", "core"}) {
      breakers.circuitBreaker(name).reset();
    }
    meters.find("gateway.fallback").counters().forEach(meters::remove);
  }

  @Test
  void breakersOnOrderReservationAndAuthRoutesNeverBlockWhileCoreBreakerIsForcedOpen() {
    breakers.circuitBreaker("core").transitionToForcedOpenState();

    client.post().uri("/api/orders").header("Origin", WEB).exchange().expectStatus().isOk();
    client
        .post()
        .uri("/api/checkout/reservations")
        .header("Origin", WEB)
        .exchange()
        .expectStatus()
        .isOk();
    client.post().uri("/api/auth/login").header("Origin", WEB).exchange().expectStatus().isOk();

    assertThat(CORE.requests())
        .containsExactly(
            "POST /api/orders", "POST /api/checkout/reservations", "POST /api/auth/login");
    client.get().uri("/api/products").exchange().expectStatus().isEqualTo(503);
    assertThat(CORE.requests()).hasSize(3);
  }

  @Test
  void cartWriteUpstreamAnsweringAfterOneAndAHalfSecondsSucceeds() {
    CART.delay(1500);

    client
        .mutate()
        .responseTimeout(Duration.ofSeconds(10))
        .build()
        .post()
        .uri("/api/cart/items")
        .header("Origin", WEB)
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void tenUpstream404sLeaveTheBreakerClosed() {
    CART.status(404);

    for (int i = 0; i < 12; i++) {
      client.get().uri("/api/cart").exchange().expectStatus().isNotFound();
    }

    assertThat(breakers.circuitBreaker("cart-read").getState())
        .isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(CART.requests()).hasSize(12);
  }

  @Test
  void tenUpstream500sOpenTheBreaker() {
    CART.status(500);

    for (int i = 0; i < 10; i++) {
      client.get().uri("/api/cart").exchange().expectStatus().isEqualTo(503);
    }

    assertThat(breakers.circuitBreaker("cart-read").getState())
        .isEqualTo(CircuitBreaker.State.OPEN);
    client.get().uri("/api/cart").exchange().expectStatus().isEqualTo(503);
    assertThat(CART.requests()).hasSize(10);
  }

  @Test
  void timeoutsCountAsFailuresAndOpenTheBreaker() {
    CORE.delay(700);

    for (int i = 0; i < 10; i++) {
      client.get().uri("/api/products").exchange().expectStatus().isEqualTo(503);
    }

    assertThat(breakers.circuitBreaker("core").getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void cartReadTimeoutFallbackIsAValidProblemAndCountedAsTimeout() {
    CART.delay(900);

    assertFallback(get("/api/cart"));
    assertThat(count("cart-read", "timeout")).isEqualTo(1);
  }

  @Test
  void coreTimeoutFallbackIsAValidProblemAndCountedAsTimeout() {
    CORE.delay(900);

    assertFallback(get("/api/products"));
    assertThat(count("core", "timeout")).isEqualTo(1);
  }

  @Test
  void cartReadUpstream500FallbackIsAValidProblemAndCountedAsUpstreamError() {
    CART.status(500);

    assertFallback(get("/api/cart"));
    assertThat(count("cart-read", "upstream_error")).isEqualTo(1);
  }

  @Test
  void coreUpstream500FallbackIsAValidProblemAndCountedAsUpstreamError() {
    CORE.status(500);

    assertFallback(get("/api/products"));
    assertThat(count("core", "upstream_error")).isEqualTo(1);
  }

  @Test
  void cartReadOpenBreakerFallbackIsAValidProblemAndCountedAsCircuitOpen() {
    breakers.circuitBreaker("cart-read").transitionToForcedOpenState();

    assertFallback(get("/api/cart"));
    assertThat(count("cart-read", "circuit_open")).isEqualTo(1);
    assertThat(CART.requests()).isEmpty();
  }

  @Test
  void coreOpenBreakerFallbackIsAValidProblemAndCountedAsCircuitOpen() {
    breakers.circuitBreaker("core").transitionToForcedOpenState();

    assertFallback(get("/api/products"));
    assertThat(count("core", "circuit_open")).isEqualTo(1);
  }

  @Test
  void breakerMetricsAreOnTheManagementPortPrometheusEndpoint() {
    String body =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + managementPort)
            .build()
            .get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertThat(body).contains("resilience4j_circuitbreaker_state").contains("cart-read");
  }

  private EntityExchangeResult<String> get(String uri) {
    return client.get().uri(uri).exchange().expectBody(String.class).returnResult();
  }

  private static void assertFallback(EntityExchangeResult<String> result) {
    assertThat(result.getStatus().value()).isEqualTo(503);
    HttpHeaders headers = result.getResponseHeaders();
    assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
    assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    String body = result.getResponseBody();
    assertThat(ProblemSchema.violations(body)).isEmpty();
    assertThat(body).contains("\"code\":\"SERVICE_UNAVAILABLE\"").contains("\"traceId\":\"");
  }

  private double count(String route, String reason) {
    var counter =
        meters.find("gateway.fallback").tag("route", route).tag("reason", reason).counter();
    return counter == null ? 0 : counter.count();
  }
}
