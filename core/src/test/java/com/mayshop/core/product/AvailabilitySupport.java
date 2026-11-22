package com.mayshop.core.product;

import com.mayshop.core.product.PriceRule.SalePrice;
import com.mayshop.core.product.persistence.AvailabilityQueries;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Test doubles for the availability endpoint that need no database. */
final class AvailabilitySupport {

  static final Instant NOW = Instant.parse("2026-12-12T10:00:00Z");

  private AvailabilitySupport() {}

  @Import(ProductApiSupport.InMemoryCatalogConfiguration.class)
  static class Configuration {

    @Bean
    @Primary
    InMemoryAvailability inMemoryAvailability() {
      return new InMemoryAvailability();
    }

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  static class InMemoryAvailability implements AvailabilityQueries {

    private final Map<String, AvailabilityRow> rows = new HashMap<>();

    void clear() {
      rows.clear();
    }

    void put(UUID productId, String sku, long price, int quantity, List<SalePrice> sales) {
      rows.put(sku, new AvailabilityRow(productId, sku, price, quantity, sales));
    }

    @Override
    public Optional<AvailabilityRow> findBySku(String sku) {
      return Optional.ofNullable(rows.get(sku));
    }
  }
}
