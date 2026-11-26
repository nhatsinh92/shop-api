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
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stock-only {@code product.updated} event keeps the stale product entry, so it can still be
 * served while {@code core} is unavailable; price and detail changes still delete both (#197).
 */
class ProductUpdatedStockOnlyTest {

  private static final Duration FRESH = Duration.ofSeconds(60);
  private static final Duration STALE = Duration.ofMinutes(10);

  private FakeRedis redis;
  private StubCore core;
  private SimpleMeterRegistry meters;
  private ProductLookupService service;
  private ProductUpdatedConsumer consumer;

  @BeforeEach
  void start() throws Exception {
    MutableClock clock = new MutableClock();
    redis = new FakeRedis(clock);
    core = new StubCore();
    meters = new SimpleMeterRegistry();
    // A generous lookup timeout: these tests are about eviction, not timeouts, and the first call
    // of a cold JVM can take longer than the production 150 ms.
    ProductLookupProperties properties =
        new ProductLookupProperties(core.url(), "t", Duration.ofSeconds(2), FRESH, STALE);
    CircuitBreakerRegistry breakers =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
    Resilience4JCircuitBreakerFactory factory =
        new Resilience4JCircuitBreakerFactory(
            breakers,
            TimeLimiterRegistry.ofDefaults(),
            null,
            new Resilience4JConfigurationProperties());
    factory.configureDefault(id ->
        new Resilience4JConfigBuilder(id)
            .circuitBreakerConfig(breakers.getDefaultConfig())
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
    consumer = new ProductUpdatedConsumer(redis.template, json);
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

  @Test
  void aStockOnlyEventDeletesTheFreshKeyAndKeepsTheStaleKey() {
    warm("SKU-1");

    consumer.onProductUpdated("{\"sku\":\"SKU-1\",\"changes\":[\"STOCK\"]}");

    assertThat(redis.get("product:SKU-1")).isNull();
    assertThat(redis.get("product-stale:SKU-1")).isNotNull();
  }

  @Test
  void aPriceDetailOrUnreadableChangesEventDeletesBothKeys() {
    String[] events = {
      "{\"sku\":\"SKU-1\",\"changes\":[\"STOCK\",\"PRICE\"]}",
      "{\"sku\":\"SKU-2\",\"changes\":[\"DETAIL\"]}",
      "{\"sku\":\"SKU-3\"}",
      "{\"sku\":\"SKU-4\",\"changes\":[]}",
      "{\"sku\":\"SKU-5\",\"changes\":\"STOCK\"}",
    };
    for (int i = 0; i < events.length; i++) {
      String sku = "SKU-" + (i + 1);
      warm(sku);

      consumer.onProductUpdated(events[i]);

      assertThat(redis.get("product:" + sku)).as(events[i]).isNull();
      assertThat(redis.get("product-stale:" + sku)).as(events[i]).isNull();
    }
  }

  @Test
  void afterAStockOnlyEventACoreErrorServesTheStaleEntry() {
    warm("SKU-1");
    consumer.onProductUpdated("{\"sku\":\"SKU-1\",\"changes\":[\"STOCK\"]}");
    core.status = 500;

    assertThat(service.lookup(Set.of("SKU-1")).known()).containsOnlyKeys("SKU-1");
    assertThat(cache("stale")).isEqualTo(1.0);
  }

  @Test
  void afterAPriceEventACoreErrorStillFails() {
    warm("SKU-1");
    consumer.onProductUpdated("{\"sku\":\"SKU-1\",\"changes\":[\"PRICE\"]}");
    core.status = 500;

    assertThatThrownBy(() -> service.lookup(Set.of("SKU-1")))
        .isInstanceOf(ProductLookupException.class);
  }
}
