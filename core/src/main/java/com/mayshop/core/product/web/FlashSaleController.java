package com.mayshop.core.product.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mayshop.core.product.persistence.FlashSaleQueries;
import com.mayshop.core.product.persistence.FlashSaleQueries.ItemRow;
import com.mayshop.core.product.persistence.FlashSaleQueries.SaleRow;
import com.mayshop.core.product.web.ProductDtos.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code getCurrentFlashSale} of {@code contracts/openapi/core.yaml}. */
@RestController
class FlashSaleController {

  /** The contract's flash sale cache policy; the 404 overrides it with no-store. */
  static final String CACHE_CONTROL = "public, max-age=60, stale-while-revalidate=120";

  static final String CACHE_TAG = "flash-sale";

  private static final String BADGE_DISCOUNT = "DISCOUNT";

  private final FlashSaleQueries queries;
  private final Clock clock;

  FlashSaleController(FlashSaleQueries queries, ObjectProvider<Clock> clock) {
    this.queries = queries;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  @GetMapping("/api/flash-sales/current")
  ResponseEntity<FlashSale> getCurrentFlashSale() {
    SaleRow sale = queries.findCurrent(Instant.now(clock)).orElseThrow(FlashSaleNoneException::new);
    List<Item> items = sale.items().stream().map(FlashSaleController::item).toList();
    return ResponseEntity.ok()
        .header("Cache-Control", CACHE_CONTROL)
        .header(ProductController.CACHE_TAGS, CACHE_TAG)
        .body(new FlashSale(sale.id(), sale.startsAt(), sale.endsAt(), items));
  }

  /** The regular card prices stay; the sale price and the sold share are added. */
  private static Item item(ItemRow row) {
    var card = row.card();
    boolean discounted = card.listPrice() != null && card.listPrice() > card.price();
    return new Item(
        card.sku(),
        card.slug(),
        card.name(),
        card.imageUrl(),
        Money.vnd(card.price()),
        discounted ? Money.vnd(card.listPrice()) : null,
        discounted ? List.of(BADGE_DISCOUNT) : List.of(),
        Money.vnd(row.salePrice()),
        soldPercent(row.sold(), row.saleStock()));
  }

  /** {@code floor(100 × sold / saleStock)}, capped at 100. */
  static int soldPercent(int sold, int saleStock) {
    if (saleStock <= 0) {
      return 0;
    }
    return (int) Math.min(100L, 100L * sold / saleStock);
  }

  record FlashSale(UUID id, Instant startsAt, Instant endsAt, List<Item> items) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Item(
      String sku,
      String slug,
      String name,
      String imageUrl,
      Money price,
      Money listPrice,
      List<String> badges,
      Money salePrice,
      int soldPercent) {}
}
