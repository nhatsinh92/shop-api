package com.mayshop.cart.restore;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.cart.repository.CartKey;
import com.mayshop.cart.repository.CartLine;
import com.mayshop.cart.repository.CartSnapshot;
import com.mayshop.cart.repository.RedisCartRepository;
import com.mayshop.cart.writebehind.store.CartSnapshotStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.json.JsonMapper;

/** Restore from {@code cart_snapshot} into a real Redis, with a fixed clock. */
@Testcontainers(disabledWithoutDocker = true)
class CartRestoreServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redis;
  private static JdbcTemplate jdbc;
  private static CartRestoreService service;

  @BeforeAll
  static void connect() {
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    redis = new StringRedisTemplate(connectionFactory);
    redis.afterPropertiesSet();
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    CartSnapshotStore store = new CartSnapshotStore(ds, JsonMapper.builder().build());
    service = new CartRestoreService(redis, store, CLOCK);
    service.restore(); // runs the migration on an empty table
  }

  @AfterAll
  static void close() {
    connectionFactory.destroy();
  }

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM cart_snapshot");
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  private void snapshot(String key, long version, Instant updatedAt, String sku, int quantity) {
    String items =
        "[{\"sku\":\"%s\",\"quantity\":%d,\"addedAt\":\"2026-11-01T00:00:00Z\"}]"
            .formatted(sku, quantity);
    jdbc.update(
        "INSERT INTO cart_snapshot (cart_key, version, items, updated_at)"
            + " VALUES (?, ?, ?::jsonb, ?::timestamptz)",
        key,
        version,
        items,
        updatedAt.toString());
  }

  @Test
  void aMissingKeyIsRestoredWithTheStoredVersionAndItems() {
    snapshot("cart:user-1", 7, Instant.parse("2026-11-24T10:00:00Z"), "SKU-A", 3);

    CartRestoreService.Result result = service.restore();

    assertThat(result).isEqualTo(new CartRestoreService.Result(1, 0, 0, 0));
    Map<Object, Object> hash = redis.opsForHash().entries("cart:user-1");
    assertThat(hash)
        .containsEntry("version", "7")
        .containsEntry("q:SKU-A", "3")
        .containsEntry(
            "t:SKU-A", String.valueOf(Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()))
        .containsEntry(
            "updatedAt", String.valueOf(Instant.parse("2026-11-24T10:00:00Z").toEpochMilli()));
  }

  @Test
  void aRestoredCartIsReadableByTheCartRepository() {
    snapshot("cart:user-2", 4, Instant.parse("2026-11-24T10:00:00Z"), "SKU-B", 2);

    service.restore();

    RedisCartRepository repository = new RedisCartRepository(redis, new SimpleMeterRegistry());
    CartSnapshot cart = repository.read(CartKey.forUser("user-2"));
    assertThat(cart.version()).isEqualTo(4);
    assertThat(cart.items())
        .extracting(CartLine::sku, CartLine::quantity)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("SKU-B", 2));
  }

  @Test
  void anExistingKeyIsNeverOverwritten() {
    snapshot("cart:user-3", 1, Instant.parse("2026-11-24T10:00:00Z"), "SKU-OLD", 1);
    redis.opsForHash().put("cart:user-3", "q:SKU-LIVE", "5");
    redis.opsForHash().put("cart:user-3", "version", "9");

    CartRestoreService.Result result = service.restore();

    assertThat(result).isEqualTo(new CartRestoreService.Result(0, 1, 0, 0));
    assertThat(redis.opsForHash().entries("cart:user-3"))
        .containsEntry("q:SKU-LIVE", "5")
        .containsEntry("version", "9")
        .doesNotContainKey("q:SKU-OLD");
  }

  @Test
  void aGuestKeyGetsTheRemainingTtlAndAUserKeyTheUserTtl() {
    // Ten days old: 20 days of the 30-day guest TTL remain.
    snapshot("cart:guest:g1", 2, NOW.minus(Duration.ofDays(10)), "SKU-A", 1);
    snapshot("cart:user-4", 2, NOW.minus(Duration.ofDays(10)), "SKU-A", 1);

    service.restore();

    assertThat(redis.getExpire("cart:guest:g1"))
        .isBetween(Duration.ofDays(20).toSeconds() - 1, Duration.ofDays(20).toSeconds());
    assertThat(redis.getExpire("cart:user-4"))
        .isBetween(CartKey.USER_TTL.toSeconds() - 1, CartKey.USER_TTL.toSeconds());
  }

  @Test
  void aGuestRowPastItsTtlIsSkippedAndNotWritten() {
    snapshot("cart:guest:old", 2, NOW.minus(Duration.ofDays(31)), "SKU-A", 1);
    snapshot("cart:guest:edge", 2, NOW.minus(Duration.ofDays(30)), "SKU-A", 1);

    CartRestoreService.Result result = service.restore();

    assertThat(result).isEqualTo(new CartRestoreService.Result(0, 0, 2, 0));
    assertThat(redis.hasKey("cart:guest:old")).isFalse();
    assertThat(redis.hasKey("cart:guest:edge")).isFalse();
  }

  @Test
  void theResultCountsRestoredExistingAndExpiredRowsAcrossPages() {
    for (int i = 0; i < CartRestoreService.PAGE_SIZE + 20; i++) {
      snapshot("cart:user-bulk-%04d".formatted(i), 1, NOW.minus(Duration.ofDays(1)), "SKU-A", 1);
    }
    redis.opsForHash().put("cart:user-bulk-0000", "version", "5");
    snapshot("cart:guest:gone", 1, NOW.minus(Duration.ofDays(40)), "SKU-A", 1);

    CartRestoreService.Result result = service.restore();

    assertThat(result)
        .isEqualTo(new CartRestoreService.Result(CartRestoreService.PAGE_SIZE + 19, 1, 1, 0));
  }

  @Test
  void aSnapshotWithUnreadableItemsIsCountedAndTheRestoreContinues() {
    jdbc.update(
        "INSERT INTO cart_snapshot (cart_key, version, items, updated_at)"
            + " VALUES ('cart:user-bad', 1, '{\"not\":\"a list\"}'::jsonb, ?::timestamptz)",
        NOW.toString());
    snapshot("cart:user-good", 1, NOW, "SKU-A", 1);

    CartRestoreService.Result result = service.restore();

    assertThat(result).isEqualTo(new CartRestoreService.Result(1, 0, 0, 1));
  }
}
