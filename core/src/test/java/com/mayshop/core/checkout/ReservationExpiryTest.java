package com.mayshop.core.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mayshop.core.checkout.Reservation.Item;
import com.mayshop.core.checkout.Reservation.Status;
import com.mayshop.core.checkout.persistence.ReservationStore;
import com.mayshop.core.product.StockHolds;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Expiry rules of {@link Reservations}, without a database. */
class ReservationExpiryTest {

  private static final Instant CREATED = Instant.parse("2026-12-12T01:00:00Z");
  private static final Instant EXPIRES = CREATED.plus(Reservations.TTL);

  private final ReservationStore store = mock(ReservationStore.class);
  private final StockHolds holds = mock(StockHolds.class);
  private final UUID user = UUID.randomUUID();

  private Reservations at(Instant now) {
    ObjectProvider<Clock> clock = mock();
    when(clock.getIfAvailable(any())).thenReturn(Clock.fixed(now, ZoneOffset.UTC));
    return new Reservations(store, holds, clock);
  }

  private Reservation active() {
    Item item = new Item("SKU-1", "N", null, "u", 2, 100, 100, false, true);
    return new Reservation(UUID.randomUUID(), user, Status.ACTIVE, CREATED, EXPIRES, List.of(item));
  }

  @Test
  void getReportsExpiredAsSoonAsNowReachesExpiresAt() {
    Reservation r = active();
    when(store.find(r.id(), user)).thenReturn(Optional.of(r));

    assertThat(at(EXPIRES.minusMillis(1)).find(r.id(), user).orElseThrow().status())
        .isEqualTo(Status.ACTIVE);
    assertThat(at(EXPIRES).find(r.id(), user).orElseThrow().status()).isEqualTo(Status.EXPIRED);
  }

  @Test
  void getKeepsTheStatusOfAReservationThatIsNotActive() {
    Reservation r = active();
    Reservation released =
        new Reservation(r.id(), user, Status.RELEASED, CREATED, EXPIRES, r.items());
    when(store.find(r.id(), user)).thenReturn(Optional.of(released));

    assertThat(at(EXPIRES.plusSeconds(5)).find(r.id(), user).orElseThrow().status())
        .isEqualTo(Status.RELEASED);
  }

  @Test
  void expireReleasesTheStockOfADueReservation() {
    Reservation r = active();
    when(store.findForUpdate(r.id())).thenReturn(Optional.of(r));
    when(store.markExpired(r.id(), EXPIRES)).thenReturn(true);

    assertThat(at(EXPIRES).expire(r.id(), EXPIRES)).isTrue();

    verify(holds).release(List.of(new StockHolds.ReleaseLine("SKU-1", 2, true)));
  }

  @Test
  void expireDoesNothingBeforeExpiresAt() {
    Reservation r = active();
    when(store.findForUpdate(r.id())).thenReturn(Optional.of(r));

    assertThat(at(EXPIRES).expire(r.id(), EXPIRES.minusMillis(1))).isFalse();

    verify(store, never()).markExpired(any(), any());
    verify(holds, never()).release(any());
  }

  @Test
  void expireReleasesNothingWhenTheReleaseGuardIsAlreadySet() {
    Reservation r = active();
    when(store.findForUpdate(r.id())).thenReturn(Optional.of(r));
    when(store.markExpired(r.id(), EXPIRES)).thenReturn(false);

    assertThat(at(EXPIRES).expire(r.id(), EXPIRES)).isFalse();

    verify(holds, never()).release(any());
  }

  @Test
  void expireIgnoresAnUnknownOrAlreadyExpiredReservation() {
    UUID unknown = UUID.randomUUID();
    when(store.findForUpdate(unknown)).thenReturn(Optional.empty());
    Reservation r = active();
    Reservation expired =
        new Reservation(r.id(), user, Status.EXPIRED, CREATED, EXPIRES, r.items());
    when(store.findForUpdate(r.id())).thenReturn(Optional.of(expired));
    Reservations reservations = at(EXPIRES);

    assertThat(reservations.expire(unknown, EXPIRES)).isFalse();
    assertThat(reservations.expire(r.id(), EXPIRES)).isFalse();
    verify(holds, never()).release(any());
  }

  @Test
  void expireDueExpiresEveryLockedDueRowAndCountsThem() {
    Reservation a = active();
    Reservation b = active();
    when(store.findDueForUpdateSkipLocked(EXPIRES, 100)).thenReturn(List.of(a, b));
    when(store.markExpired(a.id(), EXPIRES)).thenReturn(true);
    when(store.markExpired(b.id(), EXPIRES)).thenReturn(false);

    assertThat(at(EXPIRES).expireDue(EXPIRES, 100)).isEqualTo(1);
  }
}
