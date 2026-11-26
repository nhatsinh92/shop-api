package com.mayshop.cart.product;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The Spring-wired {@code product-lookup} breaker carries the epic defaults and env overrides. */
class ProductLookupBreakerConfigTest {

  private static CircuitBreakerConfig configOf(CircuitBreakerRegistry registry) {
    CircuitBreaker breaker = registry.circuitBreaker("product-lookup");
    return breaker.getCircuitBreakerConfig();
  }

  @Nested
  @SpringBootTest(
      properties = {
        "management.health.redis.enabled=false",
        "spring.kafka.listener.auto-startup=false"
      })
  class Defaults {

    @Autowired private CircuitBreakerRegistry registry;

    @Test
    void theBreakerLoadsTheEpicDefaultsFromApplicationYml() {
      CircuitBreakerConfig config = configOf(registry);

      assertThat(config.getSlidingWindowType()).isEqualTo(SlidingWindowType.COUNT_BASED);
      assertThat(config.getSlidingWindowSize()).isEqualTo(20);
      assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
      assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
      assertThat(config.getSlowCallRateThreshold()).isEqualTo(50f);
      assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofMillis(150));
      assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
      assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isTrue();
    }

    @Test
    void theBreakerStaysOpenForTenSeconds() {
      long waitMillis = configOf(registry).getWaitIntervalFunctionInOpenState().apply(1);

      assertThat(waitMillis).isEqualTo(Duration.ofSeconds(10).toMillis());
    }
  }

  @Nested
  @SpringBootTest(
      properties = {
        "management.health.redis.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "PRODUCT_LOOKUP_BREAKER_WINDOW=8",
        "PRODUCT_LOOKUP_BREAKER_OPEN_SECONDS=3",
        "PRODUCT_LOOKUP_TIMEOUT=300ms"
      })
  class EnvironmentOverrides {

    @Autowired private CircuitBreakerRegistry registry;

    @Test
    void environmentVariablesOverrideTheDefaults() {
      CircuitBreakerConfig config = configOf(registry);

      assertThat(config.getSlidingWindowSize()).isEqualTo(8);
      assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofMillis(300));
      assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
          .isEqualTo(Duration.ofSeconds(3).toMillis());
    }
  }
}
