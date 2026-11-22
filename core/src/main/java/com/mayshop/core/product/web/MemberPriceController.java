package com.mayshop.core.product.web;

import com.mayshop.core.product.PriceRule;
import com.mayshop.core.product.persistence.AvailabilityQueries;
import com.mayshop.core.product.persistence.AvailabilityQueries.AvailabilityRow;
import com.mayshop.core.product.persistence.CatalogQueries;
import com.mayshop.core.product.pricing.MemberPriceProperties;
import com.mayshop.core.product.pricing.MemberPriceRule;
import com.mayshop.core.product.pricing.MemberTier;
import com.mayshop.core.product.web.ProductDtos.Money;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** {@code getMemberPrice} of {@code contracts/openapi/core.yaml}. */
@RestController
class MemberPriceController {

  static final String CACHE_CONTROL = "private, no-store";

  private final CatalogQueries catalog;
  private final AvailabilityQueries availability;
  private final MemberPriceRule rule;
  private final Clock clock;

  MemberPriceController(
      CatalogQueries catalog,
      AvailabilityQueries availability,
      MemberPriceProperties properties,
      ObjectProvider<Clock> clock) {
    this.catalog = catalog;
    this.availability = availability;
    this.rule = properties.rule();
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  @GetMapping("/api/products/{slug}/member-price")
  ResponseEntity<MemberPrice> getMemberPrice(
      @PathVariable String slug, Authentication authentication) {
    String sku = catalog.findBySlug(slug).orElseThrow(ProductNotFoundException::new).card().sku();
    AvailabilityRow row = availability.findBySku(sku).orElseThrow(ProductNotFoundException::new);
    long base = PriceRule.currentPrice(row.variantPrice(), row.sales(), Instant.now(clock));
    MemberTier tier = tierOf(authentication);
    return ResponseEntity.ok()
        .header("Cache-Control", CACHE_CONTROL)
        .body(new MemberPrice(sku, tier, Money.vnd(rule.priceFor(base, tier))));
  }

  private static MemberTier tierOf(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken jwt) {
      return MemberTier.fromClaim(jwt.getToken().getClaim("tier"));
    }
    return MemberTier.STANDARD;
  }

  record MemberPrice(String sku, MemberTier memberTier, Money price) {}
}
