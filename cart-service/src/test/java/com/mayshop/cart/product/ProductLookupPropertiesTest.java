package com.mayshop.cart.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductLookupPropertiesTest {

  @EnableConfigurationProperties(ProductLookupProperties.class)
  static class Config {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void staleTtlDefaultsToTenMinutesAndIsOverridable() {
    runner
        .withPropertyValues("product.lookup.core-url=http://x")
        .run(ctx ->
            assertThat(ctx.getBean(ProductLookupProperties.class).staleTtl())
                .isEqualTo(Duration.ofMinutes(10)));
    runner
        .withPropertyValues("product.lookup.core-url=http://x", "product.lookup.stale-ttl=30s")
        .run(ctx ->
            assertThat(ctx.getBean(ProductLookupProperties.class).staleTtl())
                .isEqualTo(Duration.ofSeconds(30)));
  }
}
