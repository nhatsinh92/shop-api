package com.mayshop.core.product;

import com.mayshop.core.product.ProductUpdated.Change;
import com.mayshop.core.product.lookup.VariantLabel;
import com.mayshop.core.product.persistence.LookupQueries;
import com.mayshop.core.product.persistence.LookupQueries.LookupRow;
import com.mayshop.core.product.persistence.StockStore;
import com.mayshop.core.product.persistence.StockStore.SaleItem;
import com.mayshop.core.product.pricing.MemberPriceProperties;
import com.mayshop.core.product.pricing.MemberPriceRule;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes stock and confirms prices for Checkout, atomically and without overselling. Both methods
 * join the caller's transaction ({@link Propagation#MANDATORY}: calling them outside one is a
 * programming error), so a rollback of the caller undoes the stock change and publishes nothing.
 *
 * <p>Lines are processed in SKU order, so two callers holding the same SKUs in opposite request
 * order cannot deadlock. Each changed SKU publishes {@link ProductUpdated} with {@code changes =
 * [STOCK]} after the commit.
 */
@Service
public class StockHolds {

  private final StockStore store;
  private final LookupQueries lookup;
  private final MemberPriceRule memberPrice;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final Supplier<UUID> ids;

  @Autowired
  StockHolds(
      StockStore store,
      LookupQueries lookup,
      MemberPriceProperties properties,
      ApplicationEventPublisher events,
      ObjectProvider<Clock> clock) {
    this(
        store,
        lookup,
        properties.rule(),
        events,
        clock.getIfAvailable(Clock::systemUTC),
        UUID::randomUUID);
  }

  /** For tests. */
  StockHolds(
      StockStore store,
      LookupQueries lookup,
      MemberPriceRule memberPrice,
      ApplicationEventPublisher events,
      Clock clock,
      Supplier<UUID> ids) {
    this.store = store;
    this.lookup = lookup;
    this.memberPrice = memberPrice;
    this.events = events;
    this.clock = clock;
    this.ids = ids;
  }

  /** A SKU and the quantity wanted. */
  public record Line(String sku, int quantity) {
    public Line {
      if (quantity < 1) {
        throw new IllegalArgumentException("quantity must be at least 1");
      }
    }
  }

  /**
   * A line that was held.
   *
   * @param unitPrice the member price of the tier applied to {@code basePrice}
   * @param basePrice the current price before the member discount
   * @param tookSaleStock whether the line took flash sale stock (and so has the sale price)
   * @param variantLabel null for a single-variant product
   */
  public record HeldLine(
      String sku,
      int quantity,
      long unitPrice,
      long basePrice,
      boolean tookSaleStock,
      String name,
      String variantLabel,
      String imageUrl) {

    /** What {@link StockHolds#release} needs to give this line back. */
    public ReleaseLine toReleaseLine() {
      return new ReleaseLine(sku, quantity, tookSaleStock);
    }
  }

  /** A line that could not be held. {@code available} is 0 for an unknown SKU. */
  public record ShortLine(String sku, int requested, int available) {}

  /**
   * The outcome of a hold: either every line is held, or none is and {@code unavailable} lists
   * every short line.
   */
  public record HoldResult(List<HeldLine> held, List<ShortLine> unavailable) {

    public boolean succeeded() {
      return unavailable.isEmpty();
    }
  }

  /** A quantity to give back; {@code tookSaleStock} comes from the {@link HeldLine}. */
  public record ReleaseLine(String sku, int quantity, boolean tookSaleStock) {}

  /**
   * @param lines what to hold; repeated SKUs are added up
   * @param tier the caller's tier, for the member price
   * @param now the moment to price for (the flash sale window)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public HoldResult hold(Collection<Line> lines, MemberTier tier, Instant now) {
    Map<String, Integer> wanted = new TreeMap<>();
    lines.forEach(line -> wanted.merge(line.sku(), line.quantity(), Integer::sum));

    List<String> taken = new ArrayList<>();
    List<ShortLine> unavailable = new ArrayList<>();
    wanted.forEach((sku, quantity) -> {
      if (store.take(sku, quantity)) {
        taken.add(sku);
      } else {
        unavailable.add(new ShortLine(sku, quantity, store.available(sku)));
      }
    });
    if (!unavailable.isEmpty()) {
      taken.forEach(sku -> store.give(sku, wanted.get(sku)));
      return new HoldResult(List.of(), List.copyOf(unavailable));
    }

    Map<String, LookupRow> rows =
        lookup.findBySkus(wanted.keySet()).stream()
            .collect(Collectors.toMap(LookupRow::sku, row -> row));
    List<HeldLine> held = new ArrayList<>();
    wanted.forEach((sku, quantity) -> held.add(heldLine(rows.get(sku), quantity, tier, now)));
    held.forEach(line -> publishStockChange(rows.get(line.sku())));
    return new HoldResult(List.copyOf(held), List.of());
  }

  /**
   * Gives the quantities back and decrements {@code sold} only for lines that took sale stock.
   * Unknown SKUs are ignored.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void release(Collection<ReleaseLine> lines) {
    List<ReleaseLine> sorted =
        lines.stream().sorted(Comparator.comparing(ReleaseLine::sku)).toList();
    Map<String, LookupRow> rows =
        lookup.findBySkus(sorted.stream().map(ReleaseLine::sku).distinct().toList()).stream()
            .collect(Collectors.toMap(LookupRow::sku, row -> row));
    sorted.stream()
        .filter(line -> rows.containsKey(line.sku()))
        .forEach(line -> {
          store.give(line.sku(), line.quantity());
          if (line.tookSaleStock()) {
            store.giveSaleStock(line.sku(), line.quantity());
          }
        });
    sorted.stream()
        .map(ReleaseLine::sku)
        .distinct()
        .filter(rows::containsKey)
        .forEach(sku -> publishStockChange(rows.get(sku)));
  }

  private HeldLine heldLine(LookupRow row, int quantity, MemberTier tier, Instant now) {
    long base = row.availability().variantPrice();
    boolean tookSale = false;
    Optional<SaleItem> sale = store.currentSaleItem(row.sku(), now);
    if (sale.isPresent() && store.takeSaleStock(sale.get(), quantity)) {
      tookSale = true;
      base =
          PriceRule.currentPrice(
              row.availability().variantPrice(), row.availability().sales(), now);
    }
    long unit =
        memberPrice.priceFor(
            base, com.mayshop.core.product.pricing.MemberTier.valueOf(tier.name()));
    String label = row.variantCount() > 1 ? VariantLabel.of(row.options()) : null;
    return new HeldLine(
        row.sku(), quantity, unit, base, tookSale, row.name(), label, row.imageUrl());
  }

  private void publishStockChange(LookupRow row) {
    var availability = row.availability();
    events.publishEvent(
        new ProductUpdated(
            ids.get(),
            Instant.now(clock),
            availability.productId(),
            row.sku(),
            row.slug(),
            List.of(Change.STOCK),
            List.of("product-" + availability.productId())));
  }
}
