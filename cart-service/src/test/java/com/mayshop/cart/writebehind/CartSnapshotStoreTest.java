package com.mayshop.cart.writebehind;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.cart.writebehind.store.CartSnapshotStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** The Flyway migration and the version rule of the upsert, against a real Postgres. */
@Testcontainers(disabledWithoutDocker = true)
class CartSnapshotStoreTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static CartUpdated record(String key, long version, String... skus) {
    List<CartUpdated.Item> items =
        java.util.Arrays.stream(skus)
            .map(sku -> new CartUpdated.Item(sku, 1, Instant.parse("2026-11-24T10:00:00Z")))
            .toList();
    return new CartUpdated(key, version, Instant.parse("2026-11-24T10:00:00Z"), items);
  }

  private static JdbcTemplate jdbc() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return new JdbcTemplate(ds);
  }

  private static CartSnapshotStore store() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return new CartSnapshotStore(ds, JsonMapper.builder().build());
  }

  private static Map<String, Object> row(String key) {
    List<Map<String, Object>> rows =
        jdbc()
            .queryForList(
                "SELECT version, items::text AS items FROM cart_snapshot WHERE cart_key = ?", key);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Test
  void aHigherVersionReplacesTheRowAndAnOlderOrDuplicateRecordLeavesItUnchanged() {
    CartSnapshotStore store = store();
    store.apply(record("cart:u-versions", 2, "SKU-A"));
    store.apply(record("cart:u-versions", 3, "SKU-A", "SKU-B"));
    assertThat(row("cart:u-versions")).containsEntry("version", 3L);

    store.apply(record("cart:u-versions", 2, "SKU-OLD"));
    store.apply(record("cart:u-versions", 3, "SKU-DUP"));

    Map<String, Object> stored = row("cart:u-versions");
    assertThat(stored).containsEntry("version", 3L);
    assertThat((String) stored.get("items")).contains("SKU-B").doesNotContain("SKU-OLD", "SKU-DUP");
  }

  @Test
  void anEmptyCartDeletesTheRow() {
    CartSnapshotStore store = store();
    store.apply(record("cart:u-empty", 1, "SKU-A"));
    assertThat(row("cart:u-empty")).isNotNull();

    store.apply(record("cart:u-empty", 2));

    assertThat(row("cart:u-empty")).isNull();
  }

  @Test
  void theMigrationCreatesCartSnapshotInTheDefaultHistoryTable() {
    store().apply(record("cart:u-migrate", 1, "SKU-A"));

    assertThat(
            jdbc()
                .queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
                    Integer.class))
        .isEqualTo(1);
  }
}
