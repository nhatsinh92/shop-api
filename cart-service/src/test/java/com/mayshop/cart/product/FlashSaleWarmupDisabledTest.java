package com.mayshop.cart.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** The test defaults leave the warm-up off. */
@SpringBootTest(
    properties = {
      "spring.kafka.bootstrap-servers=127.0.0.1:1",
      "spring.data.redis.port=1",
      "spring.kafka.consumer.properties.default.api.timeout.ms=1000"
    })
class FlashSaleWarmupDisabledTest {

  @Autowired private ApplicationContext context;

  @Test
  void warmupDoesNotRunInTestContextsUnlessEnabled() {
    assertThat(context.getBeansOfType(FlashSaleWarmup.class)).isEmpty();
  }
}
