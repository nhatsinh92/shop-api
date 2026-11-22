package com.mayshop.core.product.revalidate;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

class RevalidateRegistrationTest {

  @Configuration(proxyBeanMethods = false)
  @Import({RevalidateWebhook.class, RevalidateAsyncConfiguration.class})
  static class Config {
    @Bean
    JsonMapper jsonMapper() {
      return JsonMapper.builder().build();
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void withoutAUrlTheListenerIsNotRegistered() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(RevalidateWebhook.class));
    runner
        .withPropertyValues("product.revalidate.url=")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(RevalidateWebhook.class));
  }

  @Test
  void withAUrlAndASecretTheListenerIsRegistered() {
    runner
        .withPropertyValues(
            "product.revalidate.url=http://localhost:3000/hooks/revalidate",
            "product.revalidate.secret=abc")
        .run(ctx -> assertThat(ctx).hasSingleBean(RevalidateWebhook.class));
  }

  @Test
  void aUrlWithoutASecretFailsTheStartup() {
    runner
        .withPropertyValues("product.revalidate.url=http://localhost:3000/hooks/revalidate")
        .run(ctx ->
            assertThat(ctx.getStartupFailure())
                .rootCause()
                .hasMessageContaining("REVALIDATE_SECRET"));
  }
}
