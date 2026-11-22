package com.mayshop.core.product.update;

import com.mayshop.core.product.ProductUpdated;
import com.mayshop.core.product.ProductUpdated.Change;
import com.mayshop.core.product.persistence.ProductUpdateStore;
import com.mayshop.core.product.persistence.ProductUpdateStore.Variant;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes the price and stock of a SKU in one transaction and publishes one {@link ProductUpdated}
 * per request that changed something. Spring Modulith keeps the event in its registry inside the
 * transaction and externalises it to Kafka after the commit.
 */
@Service
public class ProductUpdates {

  private final ProductUpdateStore store;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final Supplier<UUID> ids;

  @Autowired
  ProductUpdates(
      ProductUpdateStore store, ApplicationEventPublisher events, ObjectProvider<Clock> clock) {
    this(store, events, clock.getIfAvailable(Clock::systemUTC), UUID::randomUUID);
  }

  /** For tests: a fixed event id supplier. */
  public ProductUpdates(
      ProductUpdateStore store, ApplicationEventPublisher events, Clock clock, Supplier<UUID> ids) {
    this.store = store;
    this.events = events;
    this.clock = clock;
    this.ids = ids;
  }

  /** Requested new values; a null property is left as it is. */
  public record Request(Long price, Long listPrice, Integer stock) {}

  /**
   * @throws UnknownSkuException when no variant has this SKU
   * @throws InvalidUpdateException when the resulting list price is not above the price
   */
  @Transactional
  public void update(String sku, Request request) {
    Variant current = store.lockBySku(sku).orElseThrow(UnknownSkuException::new);
    long price = request.price() != null ? request.price() : current.price();
    Long listPrice = request.listPrice() != null ? request.listPrice() : current.listPrice();
    if (listPrice != null && listPrice <= price) {
      throw new InvalidUpdateException();
    }
    Set<Change> changes = EnumSet.noneOf(Change.class);
    if (price != current.price() || !Objects.equals(listPrice, current.listPrice())) {
      changes.add(Change.PRICE);
    }
    if (request.stock() != null && request.stock() != current.stock()) {
      changes.add(Change.STOCK);
    }
    if (changes.isEmpty()) {
      return;
    }
    store.update(sku, price, listPrice, request.stock());
    Instant now = Instant.now(clock);
    events.publishEvent(
        new ProductUpdated(
            ids.get(),
            now,
            current.productId(),
            sku,
            current.slug(),
            List.copyOf(changes),
            tags(current.productId(), sku, changes, now)));
  }

  private List<String> tags(UUID productId, String sku, Set<Change> changes, Instant now) {
    List<String> tags = new ArrayList<>();
    tags.add("product-" + productId);
    if (changes.contains(Change.PRICE) || changes.contains(Change.DETAIL)) {
      tags.add("products");
      if (store.inCurrentFlashSale(sku, now)) {
        tags.add("flash-sale");
      }
    }
    return List.copyOf(tags);
  }
}
