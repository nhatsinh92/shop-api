package com.mayshop.core.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mayshop.core.checkout.Reservation.Item;
import com.mayshop.core.checkout.Reservation.Status;
import com.mayshop.core.checkout.Reservations.ConsumeResult;
import com.mayshop.core.product.StockHolds;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@link Reservations#consume} and {@link Reservations#restock}, without a database. */
class ReservationConsumeTest {

  private static final Instant CREATED = Instant.parse("2026-12-12T01:00:00Z");
  private static final Instant EXPIRES = CREATED.plus(Reservations.TTL);
  private static final UUID USER = UUID.fromString(CheckoutSupport.USER);
  private static final UUID OTHER = UUID.fromString(CheckoutSupport.OTHER_USER);

  /** Adds the row lock reads and the consume and restock writes the base fake lacks. */
  private static class Store extends InMemoryReservationStore {
    final Map<UUID, Instant> restockedAt = new HashMap<>();

    void put(Reservation reservation) {
      insert(reservation);
    }

    void setStatus(UUID id, Status status) {
      Reservation r = get(id);
      insert(new Reservation(r.id(), r.userId(), status, r.createdAt(), r.expiresAt(), r.items()));
    }

    @Override
    public Optional<Reservation> findForUpdate(UUID id) {
      return Optional.ofNullable(get(id));
    }

    @Override
    public boolean markConsumed(UUID id) {
      if (get(id).status() != Status.ACTIVE) {
        return false;
      }
      setStatus(id, Status.CONSUMED);
      return true;
    }

    @Override
    public boolean markRestocked(UUID id, Instant now) {
      if (get(id).status() != Status.CONSUMED || restockedAt.containsKey(id)) {
        return false;
      }
      restockedAt.put(id, now);
      return true;
    }

    @Override
    public boolean markExpired(UUID id, Instant now) {
      setStatus(id, Status.EXPIRED);
      return true;
    }
  }

  private final Store store = new Store();
  private final StockHolds holds = mock(StockHolds.class);

  private Reservations at(Instant now) {
    ObjectProvider<Clock> clock = mock();
    when(clock.getIfAvailable(any())).thenReturn(Clock.fixed(now, ZoneOffset.UTC));
    return new Reservations(store, holds, clock);
  }

  private Reservation put(Status status) {
    List<Item> items =
        List.of(
            new Item("SKU-1", "N", null, "u", 2, 100, 100, false, true),
            new Item("SKU-2", "M", null, "u", 1, 250, 250, false, false));
    Reservation r = new Reservation(UUID.randomUUID(), USER, status, CREATED, EXPIRES, items);
    store.put(r);
    return r;
  }

  @Test
  void consumeReturnsTheLinesAndSubtotalAndMarksAnActiveReservationConsumed() {
    Reservation r = put(Status.ACTIVE);

    ConsumeResult result = at(EXPIRES.minusMillis(1)).consume(r.id(), USER, EXPIRES.minusMillis(1));

    assertThat(result)
        .isEqualTo(
            new ConsumeResult.Consumed(
                List.of(
                    new Reservations.ConsumedLine("SKU-1", 2, 100),
                    new Reservations.ConsumedLine("SKU-2", 1, 250)),
                450));
    assertThat(store.get(r.id()).status()).isEqualTo(Status.CONSUMED);
  }

  @Test
  void consumeOfAnotherUsersReservationIsNotFoundAndChangesNothing() {
    Reservation r = put(Status.ACTIVE);

    assertThat(at(CREATED).consume(r.id(), OTHER, CREATED))
        .isInstanceOf(ConsumeResult.NotFound.class);
    assertThat(store.get(r.id()).status()).isEqualTo(Status.ACTIVE);
  }

  @Test
  void consumeOfAnUnknownReservationIsNotFound() {
    assertThat(at(CREATED).consume(UUID.randomUUID(), USER, CREATED))
        .isInstanceOf(ConsumeResult.NotFound.class);
  }

  @Test
  void consumeOfAReservationPastExpiresAtIsExpiredAndNeverCallsExpire() {
    Reservation r = put(Status.ACTIVE);

    assertThat(at(EXPIRES).consume(r.id(), USER, EXPIRES))
        .isInstanceOf(ConsumeResult.Expired.class);

    // No row changed: still ACTIVE, nothing released. Only expire() changes it.
    assertThat(store.get(r.id()).status()).isEqualTo(Status.ACTIVE);
    assertThat(store.releasedAt(r.id())).isNull();
    verify(holds, never()).release(any());

    assertThat(at(EXPIRES).expire(r.id(), EXPIRES)).isTrue();
    assertThat(store.get(r.id()).status()).isEqualTo(Status.EXPIRED);
  }

  @Test
  void consumeOfAnExpiredReservationIsExpired() {
    Reservation r = put(Status.EXPIRED);
    assertThat(at(CREATED).consume(r.id(), USER, CREATED))
        .isInstanceOf(ConsumeResult.Expired.class);
  }

  @Test
  void consumeOfAReleasedReservationIsExpired() {
    Reservation r = put(Status.RELEASED);
    assertThat(at(CREATED).consume(r.id(), USER, CREATED))
        .isInstanceOf(ConsumeResult.Expired.class);
  }

  @Test
  void consumeOfAConsumedReservationIsConsumed() {
    Reservation r = put(Status.CONSUMED);
    assertThat(at(CREATED).consume(r.id(), USER, CREATED))
        .isInstanceOf(ConsumeResult.AlreadyConsumed.class);
  }

  @Test
  void restockReleasesTheStockOfAConsumedReservationAtMostOnce() {
    Reservation r = put(Status.CONSUMED);
    Reservations reservations = at(CREATED);

    assertThat(reservations.restock(r.id())).isTrue();
    assertThat(reservations.restock(r.id())).isFalse();

    verify(holds, times(1)).release(any());
  }

  @Test
  void restockOfAnActiveReservationReleasesNothing() {
    Reservation r = put(Status.ACTIVE);
    assertThat(at(CREATED).restock(r.id())).isFalse();
    verify(holds, never()).release(any());
  }
}
