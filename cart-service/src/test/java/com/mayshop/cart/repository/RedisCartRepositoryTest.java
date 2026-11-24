package com.mayshop.cart.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisCartRepositoryTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redis;
  private static SimpleMeterRegistry meters;
  private static RedisCartRepository repository;
  private static final AtomicLong CLOCK = new AtomicLong(1_000);

  @BeforeAll
  static void connect() {
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    redis = new StringRedisTemplate(connectionFactory);
    redis.afterPropertiesSet();
    meters = new SimpleMeterRegistry();
    Clock clock =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return Instant.ofEpochMilli(CLOCK.incrementAndGet());
          }
        };
    repository = new RedisCartRepository(redis, meters, clock);
    repository.afterSingletonsInstantiated();
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  private static CartKey user() {
    return CartKey.forUser("u-" + UUID.randomUUID());
  }

  private static CartKey guest() {
    return CartKey.forGuest(UUID.randomUUID().toString().replace("-", "").substring(0, 22));
  }

  @Test
  void keysFollowTheLayoutAndWritesRefreshTheTtl() {
    CartKey user = CartKey.forUser("42");
    CartKey guest = CartKey.forGuest("abcdefghijklmnopqrstuv");
    assertThat(user.redisKey()).isEqualTo("cart:42");
    assertThat(guest.redisKey()).isEqualTo("cart:guest:abcdefghijklmnopqrstuv");

    repository.add(user, "SKU-1", 2);
    repository.add(guest, "SKU-1", 1);

    assertThat(redis.getExpire(user.redisKey()))
        .isBetween(Duration.ofDays(90).toSeconds() - 5, Duration.ofDays(90).toSeconds());
    assertThat(redis.getExpire(guest.redisKey()))
        .isBetween(Duration.ofDays(30).toSeconds() - 5, Duration.ofDays(30).toSeconds());
    var fields = redis.opsForHash().entries(user.redisKey());
    assertThat(fields).containsKeys("q:SKU-1", "t:SKU-1", "updatedAt", "version");
    assertThat(fields.get("q:SKU-1")).isEqualTo("2");

    redis.expire(user.redisKey(), Duration.ofSeconds(100));
    repository.set(user, "SKU-1", 3);
    assertThat(redis.getExpire(user.redisKey())).isGreaterThan(Duration.ofDays(89).toSeconds());
  }

  @Test
  void addIncreasesQuantityAndKeepsTheFirstAddTime() {
    CartKey key = user();
    WriteResult first = repository.add(key, "A", 2);
    WriteResult second = repository.add(key, "A", 3);

    assertThat(first.ok()).isTrue();
    assertThat(second.snapshot().items()).hasSize(1);
    assertThat(second.snapshot().items().getFirst().quantity()).isEqualTo(5);
    assertThat(second.snapshot().items().getFirst().addedAt())
        .isEqualTo(first.snapshot().items().getFirst().addedAt());
    assertThat(second.snapshot().version()).isEqualTo(2);
  }

  @Test
  void addAboveTenUnitsReturnsQtyLimitAndChangesNothing() {
    CartKey key = user();
    repository.add(key, "A", 8);
    CartSnapshot before = repository.read(key);

    WriteResult result = repository.add(key, "A", 3);

    assertThat(result.status()).isEqualTo(WriteStatus.QTY_LIMIT);
    assertThat(repository.read(key)).isEqualTo(before);
  }

  @Test
  void addOfANewSkuBeyondFiftyReturnsItemsLimitAndChangesNothing() {
    CartKey key = user();
    for (int i = 0; i < 50; i++) {
      assertThat(repository.add(key, "S" + i, 1).ok()).isTrue();
    }
    CartSnapshot before = repository.read(key);

    assertThat(repository.add(key, "S50", 1).status()).isEqualTo(WriteStatus.ITEMS_LIMIT);
    assertThat(repository.read(key)).isEqualTo(before);
    assertThat(repository.add(key, "S0", 1).ok()).isTrue();
  }

  @Test
  void setChangesAnExistingSkuAndRejectsAnUnknownOne() {
    CartKey key = user();
    repository.add(key, "A", 1);

    WriteResult ok = repository.set(key, "A", 7);
    WriteResult unknown = repository.set(key, "B", 2);

    assertThat(ok.snapshot().items().getFirst().quantity()).isEqualTo(7);
    assertThat(unknown.status()).isEqualTo(WriteStatus.ITEM_NOT_FOUND);
    assertThat(repository.read(key).version()).isEqualTo(2);
    assertThatIllegalArgumentException().isThrownBy(() -> repository.set(key, "A", 0));
    assertThatIllegalArgumentException().isThrownBy(() -> repository.set(key, "A", 11));
  }

  @Test
  void removeUnknownSkuIsNotFoundAndRemovingTheLastSkuLeavesAnEmptyCart() {
    CartKey key = user();
    repository.add(key, "A", 1);

    assertThat(repository.remove(key, "B").status()).isEqualTo(WriteStatus.ITEM_NOT_FOUND);
    WriteResult removed = repository.remove(key, "A");

    assertThat(removed.ok()).isTrue();
    assertThat(removed.snapshot().items()).isEmpty();
    assertThat(removed.snapshot().version()).isEqualTo(2);
    assertThat(redis.opsForHash().hasKey(key.redisKey(), "t:A")).isFalse();
  }

  @Test
  void readReturnsTheSnapshotShapeNewestFirst() {
    CartKey key = user();
    repository.add(key, "OLD", 1);
    repository.add(key, "NEW", 2);

    CartSnapshot snapshot = repository.read(key);

    assertThat(snapshot.items()).extracting(CartLine::sku).containsExactly("NEW", "OLD");
    assertThat(snapshot.version()).isEqualTo(2);
    assertThat(snapshot.updatedAt()).isAfter(Instant.EPOCH);
    assertThat(repository.read(user())).isEqualTo(new CartSnapshot(List.of(), 0, Instant.EPOCH));
  }

  @Test
  void mergeSumsCapsKeepsEarlierAddTimeAndDeletesTheGuestCart() {
    CartKey user = user();
    CartKey guest = guest();
    repository.add(guest, "SHARED", 4);
    repository.add(user, "SHARED", 8);
    repository.add(user, "ONLY-USER", 1);
    repository.add(guest, "ONLY-GUEST", 2);
    Instant userAdded =
        repository.read(user).items().stream()
            .filter(l -> l.sku().equals("SHARED"))
            .findFirst()
            .orElseThrow()
            .addedAt();
    Instant guestAdded =
        repository.read(guest).items().stream()
            .filter(l -> l.sku().equals("SHARED"))
            .findFirst()
            .orElseThrow()
            .addedAt();

    MergeResult result = repository.merge(user, guest);

    assertThat(result.droppedLines()).isZero();
    assertThat(result.snapshot().items())
        .extracting(CartLine::sku, CartLine::quantity)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("SHARED", 10),
            org.assertj.core.groups.Tuple.tuple("ONLY-USER", 1),
            org.assertj.core.groups.Tuple.tuple("ONLY-GUEST", 2));
    assertThat(
            result.snapshot().items().stream()
                .filter(l -> l.sku().equals("SHARED"))
                .findFirst()
                .orElseThrow()
                .addedAt())
        .isEqualTo(guestAdded.isBefore(userAdded) ? guestAdded : userAdded);
    assertThat(redis.hasKey(guest.redisKey())).isFalse();
  }

  @Test
  void mergeDropsTheNewestGuestLinesBeyondFiftyAndCountsThem() {
    CartKey user = user();
    CartKey guest = guest();
    for (int i = 0; i < 48; i++) {
      repository.add(user, "U" + i, 1);
    }
    repository.add(guest, "G-OLDEST", 1);
    repository.add(guest, "G-MIDDLE", 1);
    repository.add(guest, "G-NEWEST", 1);
    double before = meters.get("cart.merge.dropped_lines").counter().count();

    MergeResult result = repository.merge(user, guest);

    assertThat(result.droppedLines()).isEqualTo(1);
    assertThat(result.snapshot().items()).hasSize(50);
    assertThat(result.snapshot().items())
        .extracting(CartLine::sku)
        .contains("G-OLDEST", "G-MIDDLE")
        .doesNotContain("G-NEWEST");
    assertThat(meters.get("cart.merge.dropped_lines").counter().count()).isEqualTo(before + 1);
  }

  @Test
  void mergeOfAnUnknownGuestKeyLeavesTheUserCartUnchangedAndMergingTwiceIsHarmless() {
    CartKey user = user();
    CartKey guest = guest();
    repository.add(user, "A", 1);
    CartSnapshot before = repository.read(user);

    assertThat(repository.merge(user, guest).snapshot()).isEqualTo(before);

    repository.add(guest, "B", 2);
    MergeResult first = repository.merge(user, guest);
    MergeResult second = repository.merge(user, guest);

    assertThat(second.snapshot()).isEqualTo(first.snapshot());
    assertThat(second.droppedLines()).isZero();
    assertThat(repository.read(guest).version()).isZero();
  }

  @Test
  void aWriteReloadsTheScriptsAfterNoscript() {
    CartKey key = user();
    redis.execute(
        connection -> {
          connection.scriptingCommands().scriptFlush();
          return null;
        },
        true);

    assertThat(repository.add(key, "A", 1).ok()).isTrue();
    assertThat(repository.merge(key, guest()).snapshot().items()).hasSize(1);
  }

  @Test
  void twentyParallelAddsOfOneUnitEndWithQuantityTenAndTenQtyLimitResults() throws Exception {
    CartKey key = user();
    List<WriteResult> results = runParallel(20, () -> repository.add(key, "HOT", 1));

    assertThat(results.stream().filter(WriteResult::ok)).hasSize(10);
    assertThat(results.stream().filter(r -> r.status() == WriteStatus.QTY_LIMIT)).hasSize(10);
    CartSnapshot snapshot = repository.read(key);
    assertThat(snapshot.items()).hasSize(1);
    assertThat(snapshot.items().getFirst().quantity()).isEqualTo(10);
    assertThat(snapshot.version()).isEqualTo(10);
  }

  @Test
  void parallelSetAndRemoveKeepTheVersionEqualToTheSuccessfulWritesAndTheHashConsistent()
      throws Exception {
    CartKey key = user();
    repository.add(key, "A", 1);
    AtomicLong turn = new AtomicLong();
    List<WriteResult> results =
        runParallel(
            40,
            () -> {
              long n = turn.getAndIncrement();
              return switch ((int) (n % 4)) {
                case 0 -> repository.set(key, "A", (int) (n % 10) + 1);
                case 1 -> repository.remove(key, "A");
                case 2 -> repository.add(key, "A", 1);
                default -> repository.set(key, "A", 5);
              };
            });

    long successful = results.stream().filter(WriteResult::ok).count() + 1;
    var fields = redis.<String, String>opsForHash().entries(key.redisKey());
    assertThat(Long.parseLong(fields.get("version"))).isEqualTo(successful);
    boolean hasQuantity = fields.containsKey("q:A");
    assertThat(fields.containsKey("t:A")).isEqualTo(hasQuantity);
    if (hasQuantity) {
      assertThat(Integer.parseInt(fields.get("q:A"))).isBetween(1, 10);
    }
  }

  private static List<WriteResult> runParallel(int count, Callable<WriteResult> task)
      throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
      java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
      List<Future<WriteResult>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return task.call();
            }));
      }
      start.countDown();
      List<WriteResult> results = new ArrayList<>();
      for (Future<WriteResult> future : futures) {
        results.add(future.get());
      }
      return results;
    }
  }
}
