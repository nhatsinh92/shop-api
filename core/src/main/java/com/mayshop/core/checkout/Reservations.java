package com.mayshop.core.checkout;

import com.mayshop.core.checkout.Reservation.Item;
import com.mayshop.core.checkout.Reservation.Status;
import com.mayshop.core.checkout.persistence.ReservationStore;
import com.mayshop.core.product.MemberTier;
import com.mayshop.core.product.StockHolds;
import com.mayshop.core.product.StockHolds.HeldLine;
import com.mayshop.core.product.StockHolds.HoldResult;
import com.mayshop.core.product.StockHolds.ReleaseLine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservations of cart lines: public API of the {@code checkout} module. A user has at most one
 * {@link Status#ACTIVE} reservation; creating a new one gives the old one back first.
 */
@Service
public class Reservations {

  /** A reservation lives exactly this long. */
  public static final Duration TTL = Duration.ofMinutes(10);

  private final ReservationStore store;
  private final StockHolds holds;
  private final Clock clock;

  Reservations(ReservationStore store, StockHolds holds, ObjectProvider<Clock> clock) {
    this.store = store;
    this.holds = holds;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  /**
   * A line to reserve.
   *
   * @param expectedUnitPrice the price the client showed (before the member discount), or null
   */
  public record Line(String sku, int quantity, Long expectedUnitPrice) {}

  /**
   * Replaces the caller's active reservation (if any) with a new one, in one transaction: the old
   * stock is given back, then every line is held at the confirmed price.
   *
   * @param lines distinct SKUs, in the order the reservation lists them
   * @throws OutOfStockException when any line is short; nothing changes, the previous reservation
   *     stays active with its stock held
   */
  @Transactional
  public Reservation create(UUID userId, MemberTier tier, List<Line> lines) {
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    store.lockUser(userId);
    for (Reservation previous : store.findActiveForUpdate(userId)) {
      if (store.markReleased(previous.id(), now)) {
        holds.release(
            previous.items().stream()
                .map(item -> new ReleaseLine(item.sku(), item.quantity(), item.tookSaleStock()))
                .toList());
      }
    }

    HoldResult result =
        holds.hold(
            lines.stream().map(line -> new StockHolds.Line(line.sku(), line.quantity())).toList(),
            tier,
            now);
    if (!result.succeeded()) {
      throw new OutOfStockException(result.unavailable());
    }
    List<Item> items = lines.stream().map(line -> item(line, result.held())).toList();
    Reservation reservation =
        new Reservation(UUID.randomUUID(), userId, Status.ACTIVE, now, now.plus(TTL), items);
    store.insert(reservation);
    return reservation;
  }

  /**
   * The reservation, only when it belongs to {@code userId}. An {@code ACTIVE} reservation whose
   * {@code expiresAt} has passed reads {@link Status#EXPIRED} at once, before any sweep ran.
   */
  @Transactional(readOnly = true)
  public Optional<Reservation> find(UUID id, UUID userId) {
    Instant now = Instant.now(clock);
    return store.find(id, userId).map(reservation -> readAt(reservation, now));
  }

  /**
   * Expires one reservation in its own transaction: when it is {@code ACTIVE} and {@code now >=
   * expiresAt}, it becomes {@code EXPIRED} and its stock goes back. Guarded by {@code released_at},
   * so a second call (or a concurrent sweep) releases nothing.
   *
   * @return true when this call expired the reservation and released its stock
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean expire(UUID id, Instant now) {
    return store.findForUpdate(id).map(reservation -> expireLocked(reservation, now)).orElse(false);
  }

  /**
   * One sweep run: takes up to {@code limit} due reservations that no other sweep has locked,
   * expires them and releases their stock, all in one transaction.
   *
   * @return how many reservations this run expired
   */
  @Transactional
  public int expireDue(Instant now, int limit) {
    int expired = 0;
    for (Reservation reservation : store.findDueForUpdateSkipLocked(now, limit)) {
      if (expireLocked(reservation, now)) {
        expired++;
      }
    }
    return expired;
  }

  /** One consumed line. */
  public record ConsumedLine(String sku, int quantity, long unitPrice) {}

  /** What {@link #consume} found. */
  public sealed interface ConsumeResult {

    /** The reservation was {@code ACTIVE} and is now {@code CONSUMED}. */
    record Consumed(List<ConsumedLine> lines, long subtotal) implements ConsumeResult {}

    /** Unknown, or not the caller's. */
    record NotFound() implements ConsumeResult {}

    /** {@code EXPIRED}, {@code RELEASED} or past {@code expiresAt}. */
    record Expired() implements ConsumeResult {}

    /** Already {@code CONSUMED}. */
    record AlreadyConsumed() implements ConsumeResult {}
  }

  /**
   * Consumes the caller's reservation inside the caller's transaction (it must run in one): locks
   * the row and, when it is {@code ACTIVE} and {@code now < expiresAt}, marks it {@code CONSUMED}.
   *
   * <p>It never calls {@link #expire}: that runs in a new transaction and would wait for the lock
   * this transaction holds. On {@link ConsumeResult.Expired} the caller calls {@code expire(id,
   * now)} after its own transaction has rolled back.
   */
  @Transactional
  public ConsumeResult consume(UUID id, UUID userId, Instant now) {
    Optional<Reservation> found = store.findForUpdate(id).filter(r -> r.userId().equals(userId));
    if (found.isEmpty()) {
      return new ConsumeResult.NotFound();
    }
    Reservation reservation = found.get();
    if (reservation.status() == Status.CONSUMED) {
      return new ConsumeResult.AlreadyConsumed();
    }
    if (reservation.status() != Status.ACTIVE || !now.isBefore(reservation.expiresAt())) {
      return new ConsumeResult.Expired();
    }
    if (!store.markConsumed(id)) {
      return new ConsumeResult.Expired();
    }
    List<ConsumedLine> lines =
        reservation.items().stream()
            .map(item -> new ConsumedLine(item.sku(), item.quantity(), item.unitPrice()))
            .toList();
    return new ConsumeResult.Consumed(lines, reservation.subtotal());
  }

  /**
   * Gives the stock of a consumed reservation back after a failed payment, at most once (guarded by
   * {@code restocked_at}).
   *
   * @return true when this call released the stock
   */
  @Transactional
  public boolean restock(UUID id) {
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    Optional<Reservation> reservation = store.findForUpdate(id);
    if (reservation.isEmpty() || !store.markRestocked(id, now)) {
      return false;
    }
    holds.release(
        reservation.get().items().stream()
            .map(item -> new ReleaseLine(item.sku(), item.quantity(), item.tookSaleStock()))
            .toList());
    return true;
  }

  private boolean expireLocked(Reservation reservation, Instant now) {
    if (reservation.status() != Status.ACTIVE || now.isBefore(reservation.expiresAt())) {
      return false;
    }
    if (!store.markExpired(reservation.id(), now)) {
      return false;
    }
    holds.release(
        reservation.items().stream()
            .map(item -> new ReleaseLine(item.sku(), item.quantity(), item.tookSaleStock()))
            .toList());
    return true;
  }

  private static Reservation readAt(Reservation reservation, Instant now) {
    if (reservation.status() != Status.ACTIVE || now.isBefore(reservation.expiresAt())) {
      return reservation;
    }
    return new Reservation(
        reservation.id(),
        reservation.userId(),
        Status.EXPIRED,
        reservation.createdAt(),
        reservation.expiresAt(),
        reservation.items());
  }

  private static Item item(Line line, List<HeldLine> held) {
    HeldLine h =
        held.stream()
            .filter(candidate -> candidate.sku().equals(line.sku()))
            .findFirst()
            .orElseThrow();
    boolean changed = line.expectedUnitPrice() != null && line.expectedUnitPrice() != h.basePrice();
    return new Item(
        h.sku(),
        h.name(),
        h.variantLabel(),
        h.imageUrl(),
        h.quantity(),
        h.unitPrice(),
        h.basePrice(),
        changed,
        h.tookSaleStock());
  }
}
