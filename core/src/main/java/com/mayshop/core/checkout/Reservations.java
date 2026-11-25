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

  /** The reservation, only when it belongs to {@code userId}. */
  @Transactional(readOnly = true)
  public Optional<Reservation> find(UUID id, UUID userId) {
    return store.find(id, userId);
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
