package com.mayshop.cart.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mayshop.cart.product.FakeRedis.MutableClock;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Stale-on-error behaviour of the product cache, on an in-memory Redis with a manual clock. */
class ProductLookupStaleTest {

  private static final Duration FRESH = Duration.ofSeconds(60);
  private static final Duration STALE = Duration.ofMinutes(10);

  private MutableClock clock;
  private FakeRedis redis;
  private StubCore core;
  private SimpleMeterRegistry meters;
  private ProductLookupService service;
  private ProductLookupProperties properties;
  private CircuitBreakerRegistry breakerRegistry;

  @BeforeEach
  void start() throws Exception {
    clock = new MutableClock();
    redis = new FakeRedis(clock);
    core = new StubCore();
    meters = new SimpleMeterRegistry();
    properties = new ProductLookupProperties(core.url(), "t", Duration.ofMillis(300), FRESH, STALE);
    breakerRegistry =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
    Resilience4JCircuitBreakerFactory factory =
        new Resilience4JCircuitBreakerFactory(
            breakerRegistry,
            TimeLimiterRegistry.ofDefaults(),
            null,
            new org.springframework.cloud.circuitbreaker.resilience4j
                .Resilience4JConfigurationProperties());
    factory.configureDefault(id ->
        new Resilience4JConfigBuilder(id)
            .circuitBreakerConfig(breakerRegistry.getDefaultConfig())
            .timeLimiterConfig(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build())
            .build());
    JsonMapper json = JsonMapper.builder().build();
    service =
        new ProductLookupService(
            redis.template,
            new CoreProductClient(properties, json, factory),
            json,
            properties,
            meters);
  }

  @AfterEach
  void stop() {
    core.close();
  }

  private void warm(String sku) {
    core.responseBody = "{\"items\":[" + StubCore.item(sku, 100) + "]}";
    service.lookup(Set.of(sku));
  }

  private double cache(String result) {
    return meters.get("cart.product_cache").tag("result", result).counter().count();
  }

  private double failed(String reason) {
    return meters.get("cart.product_lookup.failed").tag("reason", reason).counter().count();
  }

  @Test
  void aFreshEntryNeverCallsCore() {
    warm("SKU-1");
    core.calls.set(0);

    assertThat(service.lookup(Set.of("SKU-1")).known()).containsOnlyKeys("SKU-1");
    assertThat(core.calls).hasValue(0);
    assertThat(cache("hit")).isEqualTo(1.0);
  }

  @Test
  void aCoreErrorServesTheStaleEntry() {
    warm("SKU-1");
    clock.advance(FRESH.plusSeconds(1));
    core.status = 500;

    assertThat(service.lookup(Set.of("SKU-1")).known()).containsOnlyKeys("SKU-1");
    assertThat(failed("error")).isEqualTo(1.0);
    assertThat(cache("stale")).isEqualTo(1.0);
  }

  @Test
  void aCoreTimeoutServesTheStaleEntry() {
    warm("SKU-1");
    clock.advance(FRESH.plusSeconds(1));
    core.delayMillis = 1_000;

    assertThat(service.lookup(Set.of("SKU-1")).known()).containsOnlyKeys("SKU-1");
    assertThat(failed("timeout")).isEqualTo(1.0);
    assertThat(cache("stale")).isEqualTo(1.0);
  }

  @Test
  void anOpenBreakerServesTheStaleEntryWithoutCallingCore() {
    warm("SKU-1");
    clock.advance(FRESH.plusSeconds(1));
    core.status = 500;
    // One success from warm() and this failure are 50 %: the breaker opens.
    service.lookup(Set.of("SKU-1"));
    core.calls.set(0);

    assertThat(service.lookup(Set.of("SKU-1")).known()).containsOnlyKeys("SKU-1");
    assertThat(core.calls).hasValue(0);
    assertThat(failed("circuit_open")).isEqualTo(1.0);
  }

  @Test
  void aSkuWithoutAnyEntryStillFailsWith503Semantics() {
    core.status = 500;

    assertThatThrownBy(() -> service.lookup(Set.of("SKU-NEW")))
        .isInstanceOf(ProductLookupException.class);
    assertThat(cache("miss")).isEqualTo(1.0);
  }

  @Test
  void aStaleEntryExpiresAfterTheStaleTtl() {
    warm("SKU-1");
    clock.advance(STALE.plusSeconds(1));
    core.status = 500;

    assertThatThrownBy(() -> service.lookup(Set.of("SKU-1")))
        .isInstanceOf(ProductLookupException.class);
  }

  @Test
  void productUpdatedEvictsFreshAndStaleSoACoreOutageAnswers503() {
    warm("SKU-1");
    new ProductUpdatedConsumer(redis.template, JsonMapper.builder().build())
        .onProductUpdated("{\"sku\":\"SKU-1\",\"changes\":[\"PRICE\"]}");
    core.status = 500;

    assertThat(redis.get("product:SKU-1")).isNull();
    assertThat(redis.get("product-stale:SKU-1")).isNull();
    assertThatThrownBy(() -> service.lookup(Set.of("SKU-1")))
        .isInstanceOf(ProductLookupException.class);
  }

  @Test
  void aMissCallsCoreAndRecordsIt() {
    warm("SKU-1");

    assertThat(cache("miss")).isEqualTo(1.0);
    assertThat(redis.get("product-stale:SKU-1")).isNotNull();
  }
}
