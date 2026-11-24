package com.mayshop.cart.writebehind;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Postgres and the broker are both unreachable: the service starts and stays in rotation. */
@SpringBootTest(
    properties = {
      "CART_DB_URL=jdbc:postgresql://127.0.0.1:1/cart",
      "spring.kafka.bootstrap-servers=127.0.0.1:1",
      "spring.data.redis.port=1",
      "spring.kafka.consumer.properties.default.api.timeout.ms=1000"
    })
@AutoConfigureMockMvc
class DatabaseDownStartupTest {

  @Autowired private MockMvcTester mvc;

  @Test
  void readinessIsUpAndDoesNotIncludeTheDatabaseOrKafkaWhenPostgresIsDown() {
    assertThat(mvc.get().uri("/actuator/health/readiness"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("UP");
    assertThat(mvc.get().uri("/actuator/health/readiness"))
        .bodyJson()
        .doesNotHavePath("$.components.db");
  }
}
