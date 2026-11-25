package com.mayshop.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.InMemoryRateLimiter;
import com.mayshop.gateway.support.RecordingUpstream;
import java.util.List;
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
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Cart and checkout-write routes are split; each request reaches the right upstream. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RouteSplitGatewayTest {

  private static final String WEB = "http://localhost:3000";
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
  void routesAreDeclaredInTheOrderCartReadCartWriteOrderReservationCore() {
    List<String> ids = routeLocator.getRoutes().map(r -> r.getId()).collectList().block();

    assertThat(ids)
        .containsExactly("cart-read", "cart-write", "order-write", "reservation-write", "core");
  }

  @Test
  void cartReadRouteSendsGetAndHeadToCartService() {
    client.get().uri("/api/cart").exchange().expectStatus().isOk();
    client.head().uri("/api/cart").exchange().expectStatus().isOk();

    assertThat(CART.requests()).containsExactly("GET /api/cart", "HEAD /api/cart");
    assertThat(CORE.requests()).isEmpty();
  }

  @Test
  void cartWriteRouteSendsOtherMethodsToCartService() {
    for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
      client.method(method).uri("/api/cart/items").header("Origin", WEB).exchange();
    }

    assertThat(CART.requests())
        .containsExactly("POST /api/cart/items", "PUT /api/cart/items", "DELETE /api/cart/items");
    assertThat(CORE.requests()).isEmpty();
  }

  @Test
  void orderWriteRouteSendsPostOrdersToCore() {
    client.post().uri("/api/orders").header("Origin", WEB).exchange().expectStatus().isOk();

    assertThat(CORE.requests()).containsExactly("POST /api/orders");
    assertThat(CART.requests()).isEmpty();
  }

  @Test
  void reservationWriteRouteSendsPostReservationsToCore() {
    client
        .post()
        .uri("/api/checkout/reservations")
        .header("Origin", WEB)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(CORE.requests()).containsExactly("POST /api/checkout/reservations");
    assertThat(CART.requests()).isEmpty();
  }

  @Test
  void coreRouteStillCatchesTheRestOfApi() {
    client.get().uri("/api/orders").exchange().expectStatus().isOk();
    client.get().uri("/api/products").exchange().expectStatus().isOk();

    assertThat(CORE.requests()).containsExactly("GET /api/orders", "GET /api/products");
    assertThat(CART.requests()).isEmpty();
  }

  @Test
  void noRouteConfiguresARetryFilter() {
    boolean anyRetry =
        routeLocator
            .getRoutes()
            .any(r ->
                r.getFilters().stream().anyMatch(f -> f.toString().toLowerCase().contains("retry")))
            .block();

    assertThat(anyRetry).isFalse();
  }
}
