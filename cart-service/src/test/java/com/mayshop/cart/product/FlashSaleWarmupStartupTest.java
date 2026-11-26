package com.mayshop.cart.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** With the warm-up enabled and {@code core} down, the application still starts and is ready. */
@SpringBootTest(
    properties = {
      "cart.warmup.enabled=true",
      "product.lookup.core-url=http://127.0.0.1:1",
      "spring.kafka.bootstrap-servers=127.0.0.1:1",
      "spring.data.redis.port=1",
      "spring.kafka.consumer.properties.default.api.timeout.ms=1000"
    })
@AutoConfigureMockMvc
class FlashSaleWarmupStartupTest {

  @Autowired private MockMvcTester mvc;
  @Autowired private ApplicationContext context;

  @Test
  void startupCompletesWithCoreDownWhenWarmupIsEnabled() {
    assertThat(context.getBeansOfType(FlashSaleWarmup.class)).hasSize(1);
    assertThat(mvc.get().uri("/actuator/health/readiness"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("UP");
  }
}
