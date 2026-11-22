package com.mayshop.core.product.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mayshop.core.product.PriceRule;
import com.mayshop.core.product.availability.StockLevel;
import com.mayshop.core.product.persistence.AvailabilityQueries;
import com.mayshop.core.product.persistence.AvailabilityQueries.AvailabilityRow;
import com.mayshop.core.product.web.ProductDtos.Money;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** {@code getProductAvailability} of {@code contracts/openapi/core.yaml}. */
@RestController
class AvailabilityController {

  /** The contract's availability cache policy; error responses override it with no-store. */
  static final String CACHE_CONTROL = "public, max-age=5, stale-while-revalidate=10";

  private final AvailabilityQueries queries;
  private final Clock clock;
  private final int lowThreshold;

  AvailabilityController(
      AvailabilityQueries queries,
      ObjectProvider<Clock> clock,
      @Value("${product.low-stock-threshold}") int lowThreshold) {
    this.queries = queries;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
    this.lowThreshold = lowThreshold;
  }

  @GetMapping("/api/products/{sku}/availability")
  ResponseEntity<Availability> getProductAvailability(@PathVariable String sku) {
    AvailabilityRow row = queries.findBySku(sku).orElseThrow(ProductNotFoundException::new);
    long price = PriceRule.currentPrice(row.variantPrice(), row.sales(), Instant.now(clock));
    StockLevel level = StockLevel.of(row.quantity(), lowThreshold);
    Integer approx = level.approxQuantity(row.quantity()).stream().boxed().findFirst().orElse(null);
    return ResponseEntity.ok()
        .header("Cache-Control", CACHE_CONTROL)
        .header(ProductController.CACHE_TAGS, "product-" + row.productId())
        .body(new Availability(row.sku(), Money.vnd(price), level, approx));
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Availability(String sku, Money price, StockLevel stockLevel, Integer approxQuantity) {}
}
