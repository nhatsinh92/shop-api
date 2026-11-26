package com.mayshop.core.product.update;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.product.ProductUpdated;
import com.mayshop.core.product.ProductUpdated.Change;
import com.mayshop.core.product.persistence.SaleWindowStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The look-back and firing rules of {@link SaleWindowBoundaries} against an in-memory store. */
class SaleWindowBoundariesTest {

  private static final Instant NOW = Instant.parse("2026-12-12T10:00:00Z");
  private static final UUID SALE = new UUID(1, 1);

  /** One window per entry; boundary rows appear when recorded and are never removed. */
  private static final class Store implements SaleWindowStore {
    final List<Instant[]> windows = new ArrayList<>(); // {start, end}, all with id SALE
    final Set<Boundary> recorded = new HashSet<>();
    final Set<Boundary> fired = new HashSet<>();

    @Override
    public void recordPassed(Instant now) {
      Instant[] w = windows.get(0);
      if (!w[0].isAfter(now)) {
        recorded.add(Boundary.START);
      }
      if (!w[1].isAfter(now)) {
        recorded.add(Boundary.END);
      }
    }

    @Override
    public List<Due> lockDue(Instant now, int limit) {
      Instant[] w = windows.get(0);
      List<Due> due = new ArrayList<>();
      for (Boundary b : Boundary.values()) {
        if (recorded.contains(b) && !fired.contains(b)) {
          due.add(new Due(SALE, b, b == Boundary.START ? w[0] : w[1]));
        }
      }
      return due;
    }

    @Override
    public List<Sku> skus(UUID saleId) {
      return List.of(
          new Sku("SKU-A", new UUID(2, 1), "product-a"),
          new Sku("SKU-B", new UUID(2, 2), "product-b"));
    }

    @Override
    public void markFired(UUID saleId, Boundary boundary, Instant now) {
      fired.add(boundary);
    }
  }

  private final Store store = new Store();
  private final List<Object> published = new ArrayList<>();
  private final AtomicInteger ids = new AtomicInteger();

  private SaleWindowBoundaries job(Instant now) {
    return new SaleWindowBoundaries(
        store,
        published::add,
        Clock.fixed(now, ZoneOffset.UTC),
        Duration.ofMinutes(10),
        () -> new UUID(9, ids.incrementAndGet()));
  }

  private void window(Instant start, Instant end) {
    store.windows.add(new Instant[] {start, end});
  }

  @Test
  void aBoundaryInTheFutureDoesNotFire() {
    window(NOW.plusSeconds(60), NOW.plusSeconds(3600));

    assertThat(job(NOW).fireDue()).isZero();

    assertThat(published).isEmpty();
    assertThat(store.fired).isEmpty();
  }

  @Test
  void aRecentStartPublishesOnePriceEventPerSkuAndFiresOnce() {
    window(NOW.minusSeconds(30), NOW.plusSeconds(3600));

    assertThat(job(NOW).fireDue()).isEqualTo(2);
    assertThat(job(NOW.plusSeconds(15)).fireDue()).isZero();

    assertThat(published)
        .hasSize(2)
        .allSatisfy(e -> assertThat(e).isInstanceOf(ProductUpdated.class));
    ProductUpdated first = (ProductUpdated) published.get(0);
    assertThat(first.sku()).isEqualTo("SKU-A");
    assertThat(first.changes()).containsExactly(Change.PRICE);
    assertThat(first.occurredAt()).isEqualTo(NOW);
    assertThat(first.tags()).containsExactly("product-" + new UUID(2, 1), "products", "flash-sale");
    assertThat(store.fired).containsExactly(SaleWindowStore.Boundary.START);
  }

  @Test
  void aRecentEndPublishesOnceMoreAfterTheStartFired() {
    window(NOW.minusSeconds(3600), NOW.minusSeconds(30));
    // The start passed an hour ago and the job saw it then.
    store.recorded.add(SaleWindowStore.Boundary.START);
    store.fired.add(SaleWindowStore.Boundary.START);

    assertThat(job(NOW).fireDue()).isEqualTo(2);

    assertThat(published).hasSize(2);
    assertThat(store.fired)
        .containsExactlyInAnyOrder(SaleWindowStore.Boundary.START, SaleWindowStore.Boundary.END);
  }

  @Test
  void anOldPassedBoundaryIsRecordedAsFiredWithoutPublishing() {
    window(NOW.minusSeconds(7200), NOW.minusSeconds(3600));

    assertThat(job(NOW).fireDue()).isZero();

    assertThat(published).isEmpty();
    assertThat(store.fired)
        .containsExactlyInAnyOrder(SaleWindowStore.Boundary.START, SaleWindowStore.Boundary.END);
  }

  @Test
  void aBoundaryExactlyAtTheLookbackStillFires() {
    window(NOW.minus(Duration.ofMinutes(10)), NOW.plusSeconds(3600));

    assertThat(job(NOW).fireDue()).isEqualTo(2);
  }
}
