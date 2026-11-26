package com.mayshop.core.product.update;

import com.mayshop.core.product.ProductUpdated;
import com.mayshop.core.product.ProductUpdated.Change;
import com.mayshop.core.product.persistence.SaleWindowStore;
import com.mayshop.core.product.persistence.SaleWindowStore.Due;
import com.mayshop.core.product.persistence.SaleWindowStore.Sku;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes one {@link ProductUpdated} ({@code changes = [PRICE]}) per SKU when a flash-sale window
 * starts or ends, so shop-web and cart-service caches refresh at the boundary.
 *
 * <p>A run records the boundaries that passed, locks the unfired ones with {@code FOR UPDATE SKIP
 * LOCKED}, publishes and marks them fired in one transaction, so concurrent runs (several core
 * instances) publish each boundary once. A boundary older than the look-back is marked fired
 * without publishing: the first deploy must not announce every past sale.
 */
@Service
public class SaleWindowBoundaries {

  /** Boundaries handled per run. */
  static final int BATCH = 50;

  private final SaleWindowStore store;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final Duration lookback;
  private final Supplier<UUID> ids;

  @Autowired
  SaleWindowBoundaries(
      SaleWindowStore store,
      ApplicationEventPublisher events,
      ObjectProvider<Clock> clock,
      @Value("${product.sale-window.lookback:PT10M}") Duration lookback) {
    this(store, events, clock.getIfAvailable(Clock::systemUTC), lookback, UUID::randomUUID);
  }

  /** For tests: a fixed event id supplier. */
  public SaleWindowBoundaries(
      SaleWindowStore store,
      ApplicationEventPublisher events,
      Clock clock,
      Duration lookback,
      Supplier<UUID> ids) {
    this.store = store;
    this.events = events;
    this.clock = clock;
    this.lookback = lookback;
    this.ids = ids;
  }

  /**
   * Fires the boundaries that passed and have not fired.
   *
   * @return the number of events published
   */
  @Transactional
  public int fireDue() {
    Instant now = Instant.now(clock);
    store.recordPassed(now);
    int published = 0;
    for (Due due : store.lockDue(now, BATCH)) {
      if (!due.at().isBefore(now.minus(lookback))) {
        published += publish(due, now);
      }
      store.markFired(due.saleId(), due.boundary(), now);
    }
    return published;
  }

  private int publish(Due due, Instant now) {
    List<Sku> skus = store.skus(due.saleId());
    for (Sku sku : skus) {
      events.publishEvent(
          new ProductUpdated(
              ids.get(),
              now,
              sku.productId(),
              sku.sku(),
              sku.slug(),
              List.of(Change.PRICE),
              List.of("product-" + sku.productId(), "products", "flash-sale")));
    }
    return skus.size();
  }
}
