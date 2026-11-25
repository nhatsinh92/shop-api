package com.mayshop.core.order.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides, per {@code (user, key)}, whether a request runs, replays, waits or is rejected. Keys are
 * scoped to the user. Both operations join the caller's transaction: the {@code IN_PROGRESS} row is
 * committed or rolled back with it.
 *
 * <p>A concurrent {@code begin} with the same key blocks on the primary key until the first
 * transaction ends. If it committed, the second sees the row; if it rolled back, the second inserts
 * as new.
 */
@Repository
public class IdempotencyStore {

  /** Rows older than this are deleted by the hourly job. */
  public static final Duration RETENTION = Duration.ofHours(24);

  private final JdbcClient jdbc;
  private final Clock clock;

  IdempotencyStore(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Inserts an {@code IN_PROGRESS} row, or reports why the request must not run. */
  @Transactional(propagation = Propagation.MANDATORY)
  public BeginResult begin(UUID userId, String key, String fingerprint) {
    // ON CONFLICT DO NOTHING waits for a concurrent insert of the same key to finish first.
    int inserted =
        jdbc.sql(
                """
                insert into orders.idempotency_keys (user_id, key, request_hash, state, created_at)
                values (?, ?, ?, 'IN_PROGRESS', ?)
                on conflict (user_id, key) do nothing
                """)
            .param(userId)
            .param(key)
            .param(fingerprint)
            .param(java.sql.Timestamp.from(clock.instant()))
            .update();
    if (inserted == 1) {
      return new BeginResult.Started();
    }
    var existing =
        jdbc.sql(
                """
                select request_hash, state, response_status, response_body::text as body
                  from orders.idempotency_keys where user_id = ? and key = ?
                """)
            .param(userId)
            .param(key)
            .query((rs, n) ->
                new Existing(
                    rs.getString("request_hash"),
                    rs.getString("state"),
                    rs.getInt("response_status"),
                    rs.getString("body")))
            .optional();
    if (existing.isEmpty()) {
      // Deleted by the cleanup between the two statements: the key is free again.
      return begin(userId, key, fingerprint);
    }
    Existing row = existing.get();
    if (!row.requestHash().equals(fingerprint)) {
      return new BeginResult.Reused();
    }
    if ("COMPLETED".equals(row.state())) {
      return new BeginResult.Replay(row.status(), row.body());
    }
    return new BeginResult.InProgress();
  }

  /** Stores the answer (status, JSON body, order id) and marks the key {@code COMPLETED}. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(UUID userId, String key, UUID orderId, int status, String body) {
    int updated =
        jdbc.sql(
                """
                update orders.idempotency_keys
                   set state = 'COMPLETED', order_id = ?, response_status = ?,
                       response_body = ?::jsonb
                 where user_id = ? and key = ?
                """)
            .param(orderId)
            .param(status)
            .param(body)
            .param(userId)
            .param(key)
            .update();
    if (updated != 1) {
      throw new IllegalStateException("No idempotency row to complete for key " + key);
    }
  }

  /** Records which order an {@code IN_PROGRESS} key created, so recovery can complete the key. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void link(UUID userId, String key, UUID orderId) {
    int updated =
        jdbc.sql(
                """
                update orders.idempotency_keys set order_id = ?
                 where user_id = ? and key = ? and state = 'IN_PROGRESS'
                """)
            .param(orderId)
            .param(userId)
            .param(key)
            .update();
    if (updated != 1) {
      throw new IllegalStateException("No in-progress idempotency row to link for key " + key);
    }
  }

  /**
   * Completes the key that created {@code orderId} with the order's answer; a key that is already
   * {@code COMPLETED} is left as it is.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void completeForOrder(UUID orderId, int status, String body) {
    jdbc.sql(
            """
            update orders.idempotency_keys
               set state = 'COMPLETED', response_status = ?, response_body = ?::jsonb
             where order_id = ? and state = 'IN_PROGRESS'
            """)
        .param(status)
        .param(body)
        .param(orderId)
        .update();
  }

  /** Deletes rows created before {@code now - 24 h}; returns how many. */
  @Transactional
  public int deleteExpired() {
    return jdbc.sql("delete from orders.idempotency_keys where created_at < ?")
        .param(java.sql.Timestamp.from(clock.instant().minus(RETENTION)))
        .update();
  }

  private record Existing(String requestHash, String state, int status, String body) {}
}
