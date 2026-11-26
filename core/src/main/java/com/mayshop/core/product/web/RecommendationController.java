package com.mayshop.core.product.web;

import com.mayshop.core.flags.FeatureFlags;
import com.mayshop.core.flags.Flag;
import com.mayshop.core.product.persistence.CatalogQueries;
import com.mayshop.core.product.persistence.CatalogQueries.CardRow;
import com.mayshop.core.product.persistence.FlashSaleQueries;
import com.mayshop.core.product.persistence.FlashSaleQueries.ItemRow;
import com.mayshop.core.product.persistence.RecommendationQueries;
import com.mayshop.core.product.web.ProductDtos.Money;
import com.mayshop.core.product.web.ProductDtos.Summary;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listRecommendations} of {@code contracts/openapi/core.yaml}. The list is not personalised:
 * it depends only on {@code productSlug} and the catalog. With the {@code RECOMMENDATIONS} flag off
 * the list is empty and no query runs.
 */
@RestController
class RecommendationController {

  static final int DEFAULT_LIMIT = 8;
  static final int MAX_LIMIT = 20;

  /** The contract's policy: the answer may depend on the user, so shared caches must skip it. */
  static final String CACHE_CONTROL = "private";

  private static final String BADGE_DISCOUNT = "DISCOUNT";

  private final RecommendationQueries related;
  private final CatalogQueries catalog;
  private final FlashSaleQueries flashSales;
  private final FeatureFlags flags;
  private final Clock clock;

  RecommendationController(
      RecommendationQueries related,
      CatalogQueries catalog,
      FlashSaleQueries flashSales,
      FeatureFlags flags,
      ObjectProvider<Clock> clock) {
    this.related = related;
    this.catalog = catalog;
    this.flashSales = flashSales;
    this.flags = flags;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  @GetMapping("/api/recommendations")
  ResponseEntity<Recommendations> listRecommendations(
      @RequestParam(required = false) String productSlug,
      @RequestParam(required = false) String limit) {
    int max = parseLimit(limit);
    if (!flags.isEnabled(Flag.RECOMMENDATIONS)) {
      // Switched off during a peak: answer without touching Postgres.
      return ResponseEntity.ok()
          .header("Cache-Control", CACHE_CONTROL)
          .body(new Recommendations(List.of()));
    }
    List<CardRow> cards = null;
    if (productSlug != null && !productSlug.isBlank()) {
      cards = related.relatedTo(productSlug, max).orElse(null);
    }
    if (cards == null) {
      cards = fallback(max);
    }
    return ResponseEntity.ok()
        .header("Cache-Control", CACHE_CONTROL)
        .body(new Recommendations(cards.stream().map(RecommendationController::summary).toList()));
  }

  /** The current flash sale items, then the newest products, each product once. */
  private List<CardRow> fallback(int max) {
    List<CardRow> result = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    flashSales
        .findCurrent(Instant.now(clock))
        .ifPresent(sale -> {
          for (ItemRow item : sale.items()) {
            if (result.size() < max && seen.add(item.card().id())) {
              result.add(item.card());
            }
          }
        });
    if (result.size() < max) {
      // Ask for enough extra rows to cover the sale items that the newest list repeats.
      for (CardRow card : catalog.page(null, null, max + result.size())) {
        if (result.size() < max && seen.add(card.id())) {
          result.add(card);
        }
      }
    }
    return result;
  }

  private static Summary summary(CardRow card) {
    boolean discounted = card.listPrice() != null && card.listPrice() > card.price();
    return new Summary(
        card.sku(),
        card.slug(),
        card.name(),
        card.imageUrl(),
        Money.vnd(card.price()),
        discounted ? Money.vnd(card.listPrice()) : null,
        discounted ? List.of(BADGE_DISCOUNT) : List.of());
  }

  private static int parseLimit(String limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    try {
      int value = Integer.parseInt(limit);
      if (value < 1 || value > MAX_LIMIT) {
        throw new InvalidRecommendationLimitException();
      }
      return value;
    } catch (NumberFormatException e) {
      throw new InvalidRecommendationLimitException();
    }
  }

  record Recommendations(List<Summary> items) {}
}
