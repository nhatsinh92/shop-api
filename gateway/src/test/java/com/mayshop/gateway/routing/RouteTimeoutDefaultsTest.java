package com.mayshop.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;

/** Without any override, every route carries its documented response timeout. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RouteTimeoutDefaultsTest {

  @Autowired private RouteLocator routeLocator;

  @Test
  void routeTimeoutsDefaultToTheDocumentedValues() {
    Map<String, Object> timeouts =
        routeLocator
            .getRoutes()
            .collectMap(r -> r.getId(), r -> r.getMetadata().get("response-timeout"))
            .block();

    assertThat(timeouts)
        .hasSize(5)
        .extractingByKeys("cart-read", "cart-write", "order-write", "reservation-write", "core")
        .map(Object::toString)
        .containsExactly("300", "2000", "5000", "2000", "1000");
  }
}
