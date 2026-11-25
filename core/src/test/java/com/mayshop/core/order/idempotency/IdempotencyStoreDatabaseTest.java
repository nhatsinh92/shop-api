package com.mayshop.core.order.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.order.idempotency.BeginResult.InProgress;
import com.mayshop.core.order.idempotency.BeginResult.Replay;
import com.mayshop.core.order.idempotency.BeginResult.Reused;
import com.mayshop.core.order.idempotency.BeginResult.Started;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** {@link IdempotencyStore} on Postgres: replay, in-progress, reuse, blocking and cleanup. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class IdempotencyStoreDatabaseTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static final String KEY = UUID.randomUUID().toString();

  @Autowired private IdempotencyStore store;
  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate tx;

  @BeforeEach
  void clean() {
    jdbc.sql("delete from orders.idempotency_keys").update();
  }

  @Test
  void aNewKeyStartsAndLeavesAnInProgressRow() {
    UUID user = UUID.randomUUID();
    assertThat(begin(user, "h1")).isInstanceOf(Started.class);
    assertThat(state(user)).isEqualTo("IN_PROGRESS");
  }

  @Test
  void aCompletedKeyWithTheSameFingerprintReplaysTheStoredStatusAndBody() {
    UUID user = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    tx.executeWithoutResult(s -> {
      store.begin(user, KEY, "h1");
      store.complete(user, KEY, orderId, 201, "{\"id\":\"x\"}");
    });
    var result = begin(user, "h1");
    assertThat(result).isInstanceOf(Replay.class);
    var replay = (Replay) result;
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.body()).contains("\"id\"").contains("\"x\"");
    assertThat(
            jdbc.sql("select order_id from orders.idempotency_keys where user_id = ?")
                .param(user)
                .query(UUID.class)
                .single())
        .isEqualTo(orderId);
  }

  @Test
  void anInProgressKeyWithTheSameFingerprintReportsInProgress() {
    UUID user = UUID.randomUUID();
    tx.executeWithoutResult(s -> store.begin(user, KEY, "h1"));
    assertThat(begin(user, "h1")).isInstanceOf(InProgress.class);
  }

  @Test
  void aDifferentFingerprintReportsTheKeyAsReused() {
    UUID user = UUID.randomUUID();
    tx.executeWithoutResult(s -> store.begin(user, KEY, "h1"));
    assertThat(begin(user, "h2")).isInstanceOf(Reused.class);
    tx.executeWithoutResult(s -> store.complete(user, KEY, UUID.randomUUID(), 201, "{}"));
    assertThat(begin(user, "h2")).isInstanceOf(Reused.class);
  }

  @Test
  void keysAreScopedPerUser() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    assertThat(begin(alice, "h1")).isInstanceOf(Started.class);
    assertThat(begin(bob, "h2")).isInstanceOf(Started.class);
    assertThat(
            jdbc.sql("select count(*) from orders.idempotency_keys where key = ?")
                .param(KEY)
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void aRolledBackBeginLeavesNoRow() {
    UUID user = UUID.randomUUID();
    tx.executeWithoutResult(s -> {
      store.begin(user, KEY, "h1");
      s.setRollbackOnly();
    });
    assertThat(begin(user, "h1")).isInstanceOf(Started.class);
  }

  @Test
  void aSecondTransactionBlocksUntilTheFirstCommitsThenSeesTheRow() throws Exception {
    UUID user = UUID.randomUUID();
    var inserted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<BeginResult> first =
          pool.submit(() ->
              tx.execute(s -> {
                var r = store.begin(user, KEY, "h1");
                inserted.countDown();
                await(release);
                return r;
              }));
      assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
      Future<BeginResult> second = pool.submit(() -> begin(user, "h1"));
      assertThat(isBlocked(second)).as("second begin waits on the primary key").isTrue();
      release.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isInstanceOf(Started.class);
      assertThat(second.get(10, TimeUnit.SECONDS)).isInstanceOf(InProgress.class);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void ifTheFirstTransactionRollsBackTheSecondProceedsAsNew() throws Exception {
    UUID user = UUID.randomUUID();
    var inserted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          pool.submit(() ->
              tx.executeWithoutResult(s -> {
                store.begin(user, KEY, "h1");
                inserted.countDown();
                await(release);
                s.setRollbackOnly();
              }));
      assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
      Future<BeginResult> second = pool.submit(() -> begin(user, "h1"));
      assertThat(isBlocked(second)).isTrue();
      release.countDown();
      first.get(10, TimeUnit.SECONDS);
      assertThat(second.get(10, TimeUnit.SECONDS)).isInstanceOf(Started.class);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void theCleanupDeletesRowsOlderThan24HoursOnly() {
    Instant now = Instant.parse("2026-12-12T03:00:00Z");
    var clockStore = new IdempotencyStore(jdbc, Clock.fixed(now, ZoneOffset.UTC));
    UUID user = UUID.randomUUID();
    insertAt(user, "old", now.minusSeconds(24 * 3600 + 1));
    insertAt(user, "edge", now.minusSeconds(24 * 3600 - 1));
    insertAt(user, "new", now.minusSeconds(60));
    assertThat(clockStore.deleteExpired()).isEqualTo(1);
    assertThat(
            jdbc.sql("select key from orders.idempotency_keys order by key")
                .query(String.class)
                .list())
        .containsExactly("edge", "new");
  }

  @Test
  void completingAnUnknownKeyFails() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            tx.executeWithoutResult(
                s -> store.complete(UUID.randomUUID(), KEY, UUID.randomUUID(), 201, "{}")))
        .isInstanceOf(IllegalStateException.class);
  }

  private BeginResult begin(UUID user, String fingerprint) {
    return tx.execute(s -> store.begin(user, KEY, fingerprint));
  }

  private String state(UUID user) {
    return jdbc.sql("select state from orders.idempotency_keys where user_id = ?")
        .param(user)
        .query(String.class)
        .single();
  }

  private void insertAt(UUID user, String key, Instant createdAt) {
    jdbc.sql(
            "insert into orders.idempotency_keys (user_id, key, request_hash, state, created_at)"
                + " values (?, ?, 'h', 'IN_PROGRESS', ?)")
        .params(user, key, java.sql.Timestamp.from(createdAt))
        .update();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean isBlocked(Future<?> future) throws Exception {
    try {
      future.get(1, TimeUnit.SECONDS);
      return false;
    } catch (java.util.concurrent.TimeoutException e) {
      return true;
    }
  }
}
