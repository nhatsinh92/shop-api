package com.mayshop.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The test-only {@code config/application.yml} turns the scheduled jobs off for every core test
 * context, on top of the main {@code application.yml} rather than instead of it.
 */
class TestSchedulingDefaultsTest {

  @Configuration(proxyBeanMethods = false)
  static class Empty {}

  private static ConfigurableApplicationContext start() {
    SpringApplication app = new SpringApplication(Empty.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    return app.run();
  }

  @Test
  void everyScheduledJobIsOffInTestContexts() {
    try (ConfigurableApplicationContext context = start()) {
      Environment env = context.getEnvironment();
      assertThat(env.getProperty("checkout.sweep.enabled", Boolean.class)).isFalse();
      assertThat(env.getProperty("order.recovery.enabled", Boolean.class)).isFalse();
      assertThat(env.getProperty("order.idempotency.cleanup.enabled", Boolean.class)).isFalse();
    }
  }

  @Test
  void theMainApplicationYmlIsStillLoaded() {
    try (ConfigurableApplicationContext context = start()) {
      Environment env = context.getEnvironment();
      assertThat(env.getProperty("checkout.sweep.interval")).isNotBlank();
      assertThat(env.getProperty("spring.application.name")).isEqualTo("core");
    }
  }
}
