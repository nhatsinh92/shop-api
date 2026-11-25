package com.mayshop.core.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mayshop.core.checkout.Reservation.Status;
import com.mayshop.core.checkout.Reservations.Line;
import com.mayshop.core.product.MemberTier;
import com.mayshop.core.product.StockHolds;
import com.mayshop.core.product.StockHolds.HeldLine;
import com.mayshop.core.product.StockHolds.HoldResult;
import com.mayshop.core.product.StockHolds.ReleaseLine;
import com.mayshop.core.product.StockHolds.ShortLine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** {@link Reservations} with a fake store and a mocked {@link StockHolds}. */
class ReservationsTest {

  private static final Instant NOW = Instant.parse("2026-12-12T03:00:00Z");
  private static final UUID USER = UUID.fromString(CheckoutSupport.USER);

  private final InMemoryReservationStore store = new InMemoryReservationStore();
  private final StockHolds holds = mock(StockHolds.class);
  private Reservations reservations;

  @BeforeEach
  void setUp() {
    ObjectProvider<Clock> clock = mock();
    when(clock.getIfAvailable(any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
    reservations = new Reservations(store, holds, clock);
  }

  private static HeldLine held(String sku, int quantity, long unit, long base, boolean sale) {
    return new HeldLine(sku, quantity, unit, base, sale, "Name " + sku, null, "https://img/" + sku);
  }

  private void holdSucceeds(HeldLine... lines) {
    when(holds.hold(anyCollection(), any(), any()))
        .thenReturn(new HoldResult(List.of(lines), List.of()));
  }

  @Test
  void expiresAtIsExactlyTenMinutesAfterCreation() {
    holdSucceeds(held("A", 1, 100, 100, false));
    Reservation created =
        reservations.create(USER, MemberTier.STANDARD, List.of(new Line("A", 1, null)));
    assertThat(created.createdAt()).isEqualTo(NOW);
    assertThat(created.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    assertThat(created.status()).isEqualTo(Status.ACTIVE);
  }

  @Test
  void aNewReservationReleasesTheCallersActiveOneAndItsStock() {
    holdSucceeds(held("A", 2, 100, 100, true));
    Reservation first =
        reservations.create(USER, MemberTier.STANDARD, List.of(new Line("A", 2, null)));

    holdSucceeds(held("B", 1, 50, 50, false));
    Reservation second =
        reservations.create(USER, MemberTier.STANDARD, List.of(new Line("B", 1, null)));

    assertThat(store.get(first.id()).status()).isEqualTo(Status.RELEASED);
    assertThat(store.releasedAt(first.id())).isEqualTo(NOW);
    assertThat(store.get(second.id()).status()).isEqualTo(Status.ACTIVE);
    assertThat(store.findActiveForUpdate(USER)).containsExactly(second);

    ArgumentCaptor<Collection<ReleaseLine>> released = ArgumentCaptor.captor();
    verify(holds).release(released.capture());
    assertThat(released.getValue()).containsExactly(new ReleaseLine("A", 2, true));
  }

  @Test
  void anotherUsersReservationIsLeftAlone() {
    holdSucceeds(held("A", 1, 100, 100, false));
    Reservation other =
        reservations.create(
            UUID.fromString(CheckoutSupport.OTHER_USER),
            MemberTier.STANDARD,
            List.of(new Line("A", 1, null)));
    reservations.create(USER, MemberTier.STANDARD, List.of(new Line("A", 1, null)));
    assertThat(store.get(other.id()).status()).isEqualTo(Status.ACTIVE);
    verify(holds, never()).release(any());
  }

  @Test
  void aShortLineThrowsAndStoresNothing() {
    when(holds.hold(anyCollection(), any(), any()))
        .thenReturn(new HoldResult(List.of(), List.of(new ShortLine("A", 2, 1))));
    assertThatThrownBy(
            () -> reservations.create(USER, MemberTier.STANDARD, List.of(new Line("A", 2, null))))
        .isInstanceOfSatisfying(
            OutOfStockException.class,
            e -> assertThat(e.unavailable()).containsExactly(new ShortLine("A", 2, 1)));
    assertThat(store.all()).isEmpty();
  }

  @Test
  void priceChangedIsFalseWithoutAnExpectedPrice() {
    holdSucceeds(held("A", 1, 845_500, 890_000, false));
    Reservation created =
        reservations.create(USER, MemberTier.GOLD, List.of(new Line("A", 1, null)));
    assertThat(created.items().getFirst().priceChanged()).isFalse();
  }

  @Test
  void aMemberDiscountAloneDoesNotSetPriceChanged() {
    holdSucceeds(held("A", 1, 845_500, 890_000, false));
    Reservation created =
        reservations.create(USER, MemberTier.GOLD, List.of(new Line("A", 1, 890_000L)));
    assertThat(created.items().getFirst().priceChanged()).isFalse();
    assertThat(created.items().getFirst().unitPrice()).isEqualTo(845_500);
  }

  @Test
  void aDifferentCurrentPriceSetsPriceChanged() {
    holdSucceeds(held("A", 1, 845_500, 890_000, false));
    Reservation created =
        reservations.create(USER, MemberTier.GOLD, List.of(new Line("A", 1, 950_000L)));
    assertThat(created.items().getFirst().priceChanged()).isTrue();
  }

  @Test
  void itemsKeepTheRequestOrderAndTheSubtotalSumsTheLineTotals() {
    holdSucceeds(held("A", 1, 100, 100, false), held("B", 3, 50, 50, false));
    Reservation created =
        reservations.create(
            USER, MemberTier.STANDARD, List.of(new Line("B", 3, null), new Line("A", 1, null)));
    assertThat(created.items()).extracting(Reservation.Item::sku).containsExactly("B", "A");
    assertThat(created.subtotal()).isEqualTo(250);
  }

  @Test
  void findReturnsOnlyTheCallersReservation() {
    holdSucceeds(held("A", 1, 100, 100, false));
    Reservation created =
        reservations.create(USER, MemberTier.STANDARD, List.of(new Line("A", 1, null)));
    assertThat(reservations.find(created.id(), USER)).contains(created);
    assertThat(reservations.find(created.id(), UUID.fromString(CheckoutSupport.OTHER_USER)))
        .isEmpty();
  }
}
