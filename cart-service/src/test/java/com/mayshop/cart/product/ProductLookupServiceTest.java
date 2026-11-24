package com.mayshop.cart.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class ProductLookupServiceTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redis;

  private StubCore core;
  private SimpleMeterRegistry meters;
  private ProductLookupService service;

  @BeforeAll
  static void connect() {
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    redis = new StringRedisTemplate(connectionFactory);
    redis.afterPropertiesSet();
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  @BeforeEach
  void start() throws Exception {
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    core = new StubCore();
    meters = new SimpleMeterRegistry();
    service = newService(Duration.ofSeconds(2));
  }

  @AfterEach
  void stop() {
    core.close();
  }

  private ProductLookupService newService(Duration timeout) {
    JsonMapper json = JsonMapper.builder().build();
    ProductLookupProperties properties =
        new ProductLookupProperties(core.url(), "t", timeout, Duration.ofSeconds(60));
    return new ProductLookupService(
        redis, new CoreProductClient(properties, json), json, properties, meters);
  }

  @Test
  void cachesKnownProductsUnderProductKeyWithA60SecondTtl() {
    core.responseBody = "{\"items\":[" + StubCore.item("SKU-1", 100) + "]}";

    ProductLookupResult result = service.lookup(Set.of("SKU-1"));

    assertThat(result.known()).containsOnlyKeys("SKU-1");
    assertThat(redis.getExpire("product:SKU-1")).isBetween(55L, 60L);
    assertThat(redis.opsForValue().get("product:SKU-1")).contains("\"SKU-1\"");
  }

  @Test
  void callsCoreOnlyForTheMissingSkus() {
    core.responseBody = "{\"items\":[" + StubCore.item("SKU-1", 100) + "]}";
    service.lookup(Set.of("SKU-1"));
    core.responseBody = "{\"items\":[" + StubCore.item("SKU-2", 200) + "]}";

    ProductLookupResult result = service.lookup(Set.of("SKU-1", "SKU-2"));

    assertThat(result.known()).containsOnlyKeys("SKU-1", "SKU-2");
    assertThat(core.calls).hasValue(2);
    assertThat(core.requestBodies.get(1)).isEqualTo("{\"skus\":[\"SKU-2\"]}");
  }

  @Test
  void aFullyCachedRequestMakesNoHttpCall() {
    core.responseBody = "{\"items\":[" + StubCore.item("SKU-1", 100) + "]}";
    service.lookup(Set.of("SKU-1"));
    core.calls.set(0);

    ProductLookupResult result = service.lookup(Set.of("SKU-1"));

    assertThat(result.known()).containsOnlyKeys("SKU-1");
    assertThat(core.calls).hasValue(0);
  }

  @Test
  void unknownSkusAreCachedSoACartWithARemovedProductCallsCoreOnce() {
    ProductLookupResult first = service.lookup(Set.of("SKU-GONE"));
    ProductLookupResult second = service.lookup(Set.of("SKU-GONE"));

    assertThat(first.unknown()).containsExactly("SKU-GONE");
    assertThat(second.unknown()).containsExactly("SKU-GONE");
    assertThat(core.calls).hasValue(1);
    assertThat(redis.getExpire("product:SKU-GONE")).isBetween(55L, 60L);
  }

  @Test
  void aCachedUnknownSkuDoesNotFailWhileCoreIsDown() {
    service.lookup(Set.of("SKU-GONE"));
    core.close();

    assertThat(service.lookup(Set.of("SKU-GONE")).unknown()).containsExactly("SKU-GONE");
  }

  @Test
  void aSlowCoreRaisesLookupExceptionAndRecordsMetrics() {
    core.delayMillis = 1_000;
    ProductLookupService fast = newService(Duration.ofMillis(150));

    assertThatThrownBy(() -> fast.lookup(Set.of("SKU-1")))
        .isInstanceOf(ProductLookupException.class);
    assertThat(meters.get("cart.product_lookup.failed").counter().count()).isEqualTo(1.0);
    assertThat(meters.get("cart.product_lookup").timer().count()).isEqualTo(1L);
  }

  @Test
  void aCoreThatAnswers500RaisesLookupExceptionAndCachesNothing() {
    core.status = 500;

    assertThatThrownBy(() -> service.lookup(Set.of("SKU-1")))
        .isInstanceOf(ProductLookupException.class);
    assertThat(meters.get("cart.product_lookup.failed").counter().count()).isEqualTo(1.0);
    assertThat(redis.hasKey("product:SKU-1")).isFalse();
  }

  @Test
  void anEmptyRequestMakesNoCall() {
    assertThat(service.lookup(Set.of()).known()).isEmpty();
    assertThat(core.calls).hasValue(0);
  }
}
