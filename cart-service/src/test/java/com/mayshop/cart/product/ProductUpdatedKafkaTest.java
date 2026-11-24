package com.mayshop.cart.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/** A consumed {@code product.updated} event removes the cached SKU (Redpanda and Redis). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.kafka.consumer.auto-offset-reset=earliest")
class ProductUpdatedKafkaTest {

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);

  @Container
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.3.7");

  @DynamicPropertySource
  static void kafka(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
  }

  @Autowired private StringRedisTemplate redis;
  @Autowired private KafkaTemplate<String, String> kafka;

  @Test
  void aCachedSkuIsGoneAfterItsEventIsConsumed() {
    redis.opsForValue().set("product:SKU-ANC-PRO-BLK", "{\"sku\":\"SKU-ANC-PRO-BLK\"}");
    redis.opsForValue().set("product:SKU-OTHER", "{\"sku\":\"SKU-OTHER\"}");

    kafka.send(
        "product.updated",
        "SKU-ANC-PRO-BLK",
        "{\"sku\":\"SKU-ANC-PRO-BLK\",\"changes\":[\"STOCK\"]}");
    kafka.send("product.updated", "bad", "not json");

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(redis.hasKey("product:SKU-ANC-PRO-BLK")).isFalse());
    assertThat(redis.hasKey("product:SKU-OTHER")).isTrue();
  }
}
