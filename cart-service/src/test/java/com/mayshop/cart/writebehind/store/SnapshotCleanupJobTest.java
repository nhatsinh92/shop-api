package com.mayshop.cart.writebehind.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** The nightly guest snapshot cleanup against a real Postgres, with a fixed clock. */
@Testcontainers(disabledWithoutDocker = true)
class SnapshotCleanupJobTest {

  /** Guest TTL is 30 days, so the cutoff is 2026-10-26T00:00:00Z. */
  private static final Clock NOW =
      Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private JdbcTemplate jdbc;
  private SnapshotCleanupJob job;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(ds);
    CartSnapshotStore store = new CartSnapshotStore(ds, JsonMapper.builder().build());
    // Any call runs the migration; this one also leaves the table empty for the test.
    store.deleteExpiredGuestSnapshots(Instant.EPOCH, 1);
    jdbc.update("DELETE FROM cart_snapshot");
    job = new SnapshotCleanupJob(store, NOW);
  }

  private void insert(String key, String updatedAt) {
    jdbc.update(
        "INSERT INTO cart_snapshot (cart_key, version, items, updated_at)"
            + " VALUES (?, 1, '[]'::jsonb, ?::timestamptz)",
        key,
        updatedAt);
  }

  private List<String> keys() {
    return jdbc.queryForList("SELECT cart_key FROM cart_snapshot ORDER BY cart_key", String.class);
  }

  @Test
  void oldGuestRowsAreDeletedAndRecentGuestRowsAndOldUserRowsAreKept() {
    insert("cart:guest:old-one", "2026-10-01T00:00:00Z");
    insert("cart:guest:old-two", "2026-10-25T23:59:59Z");
    insert("cart:guest:recent", "2026-11-20T00:00:00Z");
    insert("cart:guest:just-inside", "2026-10-26T00:00:01Z");
    insert("cart:user-old", "2026-01-01T00:00:00Z");

    SnapshotCleanupJob.Result result = job.run();

    assertThat(result.deleted()).isEqualTo(2);
    assertThat(keys())
        .containsExactly("cart:guest:just-inside", "cart:guest:recent", "cart:user-old");
  }

  @Test
  void moreThanOneThousandOldGuestRowsAreDeletedInSeveralBatches() {
    jdbc.update(
        "INSERT INTO cart_snapshot (cart_key, version, items, updated_at)"
            + " SELECT 'cart:guest:bulk-' || g, 1, '[]'::jsonb, '2026-09-01T00:00:00Z'::timestamptz"
            + " FROM generate_series(1, 2500) g");
    insert("cart:guest:recent", "2026-11-24T00:00:00Z");
    insert("cart:user-old", "2026-01-01T00:00:00Z");

    SnapshotCleanupJob.Result result = job.run();

    assertThat(result.deleted()).isEqualTo(2500);
    assertThat(result.batches()).isEqualTo(3);
    assertThat(keys()).containsExactly("cart:guest:recent", "cart:user-old");
  }

  @Test
  void nothingToDeleteLeavesEverythingAndReportsNoBatch() {
    insert("cart:guest:recent", "2026-11-24T00:00:00Z");

    SnapshotCleanupJob.Result result = job.run();

    assertThat(result).isEqualTo(new SnapshotCleanupJob.Result(0, 0));
    assertThat(keys()).containsExactly("cart:guest:recent");
  }

  @Test
  void aFailingStoreIsLoggedAndNeverEscapesTheScheduledMethod() {
    CartSnapshotStore broken =
        new CartSnapshotStore(
            new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "u", "p"),
            JsonMapper.builder().build());

    assertThatCode(() -> new SnapshotCleanupJob(broken, NOW).scheduled())
        .doesNotThrowAnyException();
  }
}
