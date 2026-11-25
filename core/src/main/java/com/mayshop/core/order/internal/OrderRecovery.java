package com.mayshop.core.order.internal;

import com.mayshop.core.checkout.Reservations;
import com.mayshop.core.order.idempotency.IdempotencyStore;
import com.mayshop.core.order.persistence.OrderStore;
import com.mayshop.core.order.persistence.OrderStore.Locked;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settles online orders whose payment never resolved, for example because core restarted during the
 * charge. An order still {@code PENDING_PAYMENT} {@link #STALE_AFTER} after creation becomes {@code
 * PAYMENT_FAILED} with payment {@code TIMED_OUT}, its stock goes back (once) and its idempotency
 * key is completed with the order's 201 response, so a retry replays it.
 */
@Service
public class OrderRecovery {

  /** An order pending for longer than this is recovered. */
  public static final Duration STALE_AFTER = Duration.ofMinutes(2);

  /** Orders taken per run. */
  public static final int BATCH = 100;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final OrderStore orders;
  private final IdempotencyStore idempotency;
  private final Reservations reservations;
  private final Counter recovered;

  OrderRecovery(
      OrderStore orders,
      IdempotencyStore idempotency,
      Reservations reservations,
      MeterRegistry meters) {
    this.orders = orders;
    this.idempotency = idempotency;
    this.reservations = reservations;
    this.recovered = Counter.builder("order.payment.recovered").register(meters);
  }

  /**
   * One run, in one transaction: takes up to {@code limit} stale orders that no other run has
   * locked (rows are skipped, not waited for) and settles each.
   *
   * @return how many orders this run recovered
   */
  @Transactional
  public int recoverStale(Instant now, int limit) {
    Instant at = now.truncatedTo(ChronoUnit.MICROS);
    List<Locked> stale = orders.lockStalePending(at.minus(STALE_AFTER), limit);
    for (Locked order : stale) {
      orders.updateStatus(order.id(), "PAYMENT_FAILED", "TIMED_OUT", at);
      reservations.restock(order.reservationId());
      OrderView view = orders.find(order.id(), order.userId()).orElseThrow();
      idempotency.completeForOrder(order.id(), 201, JSON.writeValueAsString(view));
    }
    if (!stale.isEmpty()) {
      recovered.increment(stale.size());
    }
    return stale.size();
  }
}
