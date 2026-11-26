package com.mayshop.cart.product;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The {@code product.lookup} block of {@code application.yml}. An entry is fresh for {@code
 * cacheTtl} and usable as stale for {@code staleTtl} when {@code core} cannot answer.
 */
@ConfigurationProperties("product.lookup")
public record ProductLookupProperties(
    String coreUrl, String internalToken, Duration timeout, Duration cacheTtl, Duration staleTtl) {

  @ConstructorBinding
  public ProductLookupProperties(
      String coreUrl,
      String internalToken,
      Duration timeout,
      Duration cacheTtl,
      Duration staleTtl) {
    this.coreUrl = coreUrl;
    this.internalToken = internalToken;
    this.timeout = timeout == null ? Duration.ofMillis(150) : timeout;
    this.cacheTtl = cacheTtl == null ? Duration.ofSeconds(60) : cacheTtl;
    this.staleTtl = staleTtl == null ? Duration.ofMinutes(10) : staleTtl;
  }

  /** The default stale window. */
  public ProductLookupProperties(
      String coreUrl, String internalToken, Duration timeout, Duration cacheTtl) {
    this(coreUrl, internalToken, timeout, cacheTtl, null);
  }
}
