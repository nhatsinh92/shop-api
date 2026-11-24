package com.mayshop.cart.product;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code product.lookup} block of {@code application.yml}. */
@ConfigurationProperties("product.lookup")
public record ProductLookupProperties(
    String coreUrl, String internalToken, Duration timeout, Duration cacheTtl) {

  public ProductLookupProperties {
    if (timeout == null) {
      timeout = Duration.ofMillis(150);
    }
    if (cacheTtl == null) {
      cacheTtl = Duration.ofSeconds(60);
    }
  }
}
