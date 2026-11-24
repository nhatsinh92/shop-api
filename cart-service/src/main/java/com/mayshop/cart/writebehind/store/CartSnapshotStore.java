package com.mayshop.cart.writebehind.store;

import com.mayshop.cart.writebehind.CartUpdated;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only class that talks to the {@code cart} database. Flyway runs when the consumer first
 * connects (and again after a failure), so Postgres being down at start-up never stops the service;
 * the consumer retries until the migration and the write succeed.
 */
@Component
public class CartSnapshotStore {

  private static final Logger LOG = LoggerFactory.getLogger(CartSnapshotStore.class);

  private static final String UPSERT =
      """
      INSERT INTO cart_snapshot (cart_key, version, items, updated_at)
      VALUES (?, ?, ?::jsonb, ?)
      ON CONFLICT (cart_key) DO UPDATE
        SET version = EXCLUDED.version,
            items = EXCLUDED.items,
            updated_at = EXCLUDED.updated_at
        WHERE cart_snapshot.version < EXCLUDED.version
      """;

  private final DataSource dataSource;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private volatile boolean migrated;

  public CartSnapshotStore(DataSource dataSource, JsonMapper json) {
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
    this.json = json;
  }

  /**
   * Applies one record: an empty cart deletes the row, any other record replaces it only when its
   * version is higher than the stored one.
   *
   * @throws RuntimeException when the database is unavailable (the caller retries)
   */
  public void apply(CartUpdated record) {
    migrateOnce();
    if (record.items().isEmpty()) {
      jdbc.update("DELETE FROM cart_snapshot WHERE cart_key = ?", record.cartKey());
      return;
    }
    jdbc.update(
        UPSERT,
        record.cartKey(),
        record.version(),
        itemsJson(record),
        Timestamp.from(record.updatedAt()));
  }

  private void migrateOnce() {
    if (migrated) {
      return;
    }
    synchronized (this) {
      if (!migrated) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        migrated = true;
        LOG.info("cart database is migrated");
      }
    }
  }

  private String itemsJson(CartUpdated record) {
    try {
      return json.writeValueAsString(record.items());
    } catch (JacksonException e) {
      throw new IllegalStateException("Cannot serialize cart items", e);
    }
  }
}
