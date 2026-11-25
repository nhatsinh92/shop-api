package com.mayshop.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.InMemoryRateLimiter;
import com.mayshop.gateway.support.RecordingUpstream;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Every upstream call is bounded by its route's response timeout. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RouteTimeoutGatewayTest {

  private static final RecordingUpstream CORE = new RecordingUpstream();
  private static final RecordingUpstream CART = new RecordingUpstream();

  @TestConfiguration(proxyBeanMethods = false)
  static class Config {
    @Bean
    @Primary
    InMemoryRateLimiter inMemoryRateLimiter() {
      return new InMemoryRateLimiter();
    }
  }

  @Autowired private WebTestClient client;
  @Autowired private InMemoryRateLimiter limiter;
  @Autowired private RouteLocator routeLocator;

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

  @BeforeEach
  void reset() {
    limiter.reset();
    CORE.reset();
    CART.reset();
  }

  @Test
  void routeTimeoutsDefaultToTheDocumentedValues() {
    Map<String, Object> metadata =
        Map.copyOf(
            routeLocator
                .getRoutes()
                .collectMap(r -> r.getId(), r -> r.getMetadata().get("response-timeout"))
                .block());

    assertThat(metadata.get("cart-write")).hasToString("2000");
    assertThat(metadata.get("order-write")).hasToString("5000");
    assertThat(metadata.get("reservation-write")).hasToString("2000");
  }

  @Test
  void cartReadUpstreamSlowerThanItsTimeoutIsCutOff() {
    CART.delay(1500);

    long elapsed = elapsedOf("/api/cart", 300);

    assertThat(elapsed).isLessThan(300 + 200 + 400);
  }

  @Test
  void coreUpstreamSlowerThanItsTimeoutIsCutOff() {
    CORE.delay(1500);

    long elapsed = elapsedOf("/api/products", 400);

    assertThat(elapsed).isLessThan(400 + 200 + 400);
  }

  @Test
  void upstreamsAnsweringWithinTheTimeoutSucceed() {
    CART.delay(50);
    CORE.delay(50);

    client.get().uri("/api/cart").exchange().expectStatus().isOk();
    client.get().uri("/api/products").exchange().expectStatus().isOk();
  }

  private long elapsedOf(String uri, long timeoutMillis) {
    long start = System.nanoTime();
    EntityExchangeResult<byte[]> result =
        client
            .mutate()
            .responseTimeout(Duration.ofSeconds(10))
            .build()
            .get()
            .uri(uri)
            .exchange()
            .expectBody()
            .returnResult();
    long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();

    assertThat(result.getStatus().is5xxServerError()).isTrue();
    assertThat(elapsed).isGreaterThanOrEqualTo(timeoutMillis - 50);
    return elapsed;
  }
}
