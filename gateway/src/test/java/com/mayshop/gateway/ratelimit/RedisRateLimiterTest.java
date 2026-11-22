package com.mayshop.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisRateLimiterTest {

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

  @DynamicPropertySource
  static void redis(DynamicPropertyRegistry registry) {
    registry.add("REDIS_HOST", REDIS::getHost);
    registry.add("REDIS_PORT", () -> REDIS.getMappedPort(6379));
  }

  @Autowired private ReactiveStringRedisTemplate template;

  private RedisRateLimiter limiter() {
    return new RedisRateLimiter(
        template,
        RedisScript.of(new ClassPathResource("ratelimit/window.lua"), String.class),
        RedisScript.of(new ClassPathResource("ratelimit/bucket.lua"), String.class));
  }

  @Test
  void aWindowAllowsTheLimitThenDeniesWithRetryAfterWithinAMinute() {
    String key = UUID.randomUUID().toString();
    RedisRateLimiter limiter = limiter();
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.perMinute(key, 3).block(Duration.ofSeconds(5)).allowed()).isTrue();
    }
    RateLimiter.Decision denied = limiter.perMinute(key, 3).block(Duration.ofSeconds(5));
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
  }

  @Test
  void aBucketAllowsTheBurstThenDeniesAndRefills() throws InterruptedException {
    String key = UUID.randomUUID().toString();
    RedisRateLimiter limiter = limiter();
    for (int i = 0; i < 4; i++) {
      assertThat(limiter.bucket(key, 2, 4).block(Duration.ofSeconds(5)).allowed()).isTrue();
    }
    RateLimiter.Decision denied = limiter.bucket(key, 2, 4).block(Duration.ofSeconds(5));
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
    Thread.sleep(1100);
    assertThat(limiter.bucket(key, 2, 4).block(Duration.ofSeconds(5)).allowed()).isTrue();
  }

  @BeforeAll
  static void started() {
    assertThat(REDIS.isRunning()).isTrue();
  }
}
