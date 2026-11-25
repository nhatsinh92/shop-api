package com.mayshop.gateway.fallback;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;

/**
 * The breakers carry the epic defaults, with the slow-call threshold equal to the route timeout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BreakerConfigTest {

  @Autowired private CircuitBreakerRegistry registry;
  @Autowired private Resilience4JConfigurationProperties cloudProperties;

  @Test
  void breakersUseTheEpicDefaults() {
    Map<String, Duration> slow =
        Map.of(
            "cart-read", Duration.ofMillis(300),
            "cart-write", Duration.ofMillis(2000),
            "core", Duration.ofMillis(1000));

    slow.forEach((name, slowThreshold) -> {
      CircuitBreakerConfig config = registry.circuitBreaker(name).getCircuitBreakerConfig();

      assertThat(config.getSlidingWindowType())
          .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
      assertThat(config.getSlidingWindowSize()).isEqualTo(20);
      assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
      assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
      assertThat(config.getSlowCallRateThreshold()).isEqualTo(50f);
      assertThat(config.getSlowCallDurationThreshold()).isEqualTo(slowThreshold);
      assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
      assertThat(registry.circuitBreaker(name).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    });
  }

  @Test
  void openStateLastsTenSeconds() {
    CircuitBreakerConfig config = registry.circuitBreaker("core").getCircuitBreakerConfig();

    assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
        .isEqualTo(Duration.ofSeconds(10).toMillis());
  }

  @Test
  void theTimeLimiterIsDisabledSoTheRouteTimeoutGoverns() {
    assertThat(cloudProperties.isDisableTimeLimiter()).isTrue();
  }

  @Test
  void onlyTheThreeUpstreamClassesHaveBreakers() {
    assertThat(registry.getAllCircuitBreakers().stream().map(CircuitBreaker::getName).toList())
        .containsExactlyInAnyOrder("cart-read", "cart-write", "core");
  }
}
