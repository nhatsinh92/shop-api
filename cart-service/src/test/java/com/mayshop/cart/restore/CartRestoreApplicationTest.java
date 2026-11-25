package com.mayshop.cart.restore;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The restore mode as the process runs it: exit codes, no HTTP, no Kafka. */
@Testcontainers(disabledWithoutDocker = true)
class CartRestoreApplicationTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);

  private static String[] args(String redisPort, String jdbcUrl) {
    return new String[] {
      "--cart.restore=true",
      "--spring.data.redis.host=" + REDIS.getHost(),
      "--spring.data.redis.port=" + redisPort,
      "--spring.datasource.url=" + jdbcUrl,
      "--spring.datasource.username=" + POSTGRES.getUsername(),
      "--spring.datasource.password=" + POSTGRES.getPassword(),
      // A broker that does not exist: restore mode must not even try to reach it.
      "--spring.kafka.bootstrap-servers=127.0.0.1:1",
      "--server.port=0"
    };
  }

  @Test
  void restoreRestoresMissingCartsAndReturnsExitCodeZero() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    int first =
        CartRestoreApplication.run(
            args(String.valueOf(REDIS.getMappedPort(6379)), POSTGRES.getJdbcUrl()));
    assertThat(first).isZero(); // migrated an empty table
    jdbc.update(
        "INSERT INTO cart_snapshot (cart_key, version, items, updated_at)"
            + " VALUES ('cart:user-exit', 3,"
            + " '[{\"sku\":\"SKU-A\",\"quantity\":2,\"addedAt\":\"2026-11-01T00:00:00Z\"}]'::jsonb,"
            + " ?::timestamptz)",
        Instant.now().toString());

    int exit =
        CartRestoreApplication.run(
            args(String.valueOf(REDIS.getMappedPort(6379)), POSTGRES.getJdbcUrl()));

    assertThat(exit).isZero();
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    factory.afterPropertiesSet();
    try {
      StringRedisTemplate redis = new StringRedisTemplate(factory);
      redis.afterPropertiesSet();
      assertThat(redis.opsForHash().get("cart:user-exit", "q:SKU-A")).isEqualTo("2");
    } finally {
      factory.destroy();
    }
  }

  @Test
  void anUnreachablePostgresReturnsAnExitCodeAboveZero() {
    int exit =
        CartRestoreApplication.run(
            args(String.valueOf(REDIS.getMappedPort(6379)), "jdbc:postgresql://127.0.0.1:1/cart"));

    assertThat(exit).isNotZero();
  }

  @Test
  void anUnreachableRedisReturnsAnExitCodeAboveZero() {
    int exit = CartRestoreApplication.run(args("1", POSTGRES.getJdbcUrl()));

    assertThat(exit).isNotZero();
  }

  @Test
  void theFlagIsRecognisedOnlyWhenTrue() {
    assertThat(CartRestoreApplication.requested("--cart.restore=true")).isTrue();
    assertThat(CartRestoreApplication.requested("--cart.restore=false")).isFalse();
    assertThat(CartRestoreApplication.requested("--server.port=0")).isFalse();
    assertThat(CartRestoreApplication.requested()).isFalse();
  }
}
