package com.mayshop.cart.writebehind.store;

import com.mayshop.cart.writebehind.CartUpdated;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
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

  /**
   * Deletes at most {@code batchSize} guest snapshots (keys {@code cart:guest:*}) last written
   * before {@code cutoff}. User snapshots are never touched.
   *
   * @return the number of rows deleted
   */
  public int deleteExpiredGuestSnapshots(Instant cutoff, int batchSize) {
    migrateOnce();
    return jdbc.update(
        """
        DELETE FROM cart_snapshot WHERE cart_key IN (
          SELECT cart_key FROM cart_snapshot
          WHERE cart_key LIKE 'cart:guest:%' AND updated_at < ?
          ORDER BY updated_at
          LIMIT ?)
        """,
        Timestamp.from(cutoff), batchSize);
  }

  /**
   * Reads one page of snapshots in key order, for the restore.
   *
   * @param afterKey the last key of the previous page, or an empty string for the first page
   * @throws RuntimeException when the database is unavailable
   */
  public List<StoredCart> findPage(String afterKey, int limit) {
    migrateOnce();
    return jdbc.query(
        """
        SELECT cart_key, version, items::text AS items, updated_at
        FROM cart_snapshot WHERE cart_key > ? ORDER BY cart_key LIMIT ?
        """,
        (rs, row) ->
            new StoredCart(
                rs.getString("cart_key"),
                rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant(),
                parseItems(rs.getString("cart_key"), rs.getString("items"))),
        afterKey,
        limit);
  }

  /** The lines of a stored cart, or {@code null} when the stored JSON cannot be read. */
  private List<CartUpdated.Item> parseItems(String cartKey, String items) {
    try {
      return json.readValue(items, new TypeReference<List<CartUpdated.Item>>() {});
    } catch (JacksonException e) {
      LOG.warn("Cannot read the items of snapshot {}", cartKey);
      return null;
    }
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
