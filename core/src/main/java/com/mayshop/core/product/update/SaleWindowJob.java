package com.mayshop.core.product.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs {@link SaleWindowBoundaries} every {@code product.sale-window.interval} ({@code
 * PRODUCT_SALE_WINDOW_INTERVAL}, default 15 s); {@code product.sale-window.enabled=false} turns it
 * off, for tests.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    name = "product.sale-window.enabled",
    havingValue = "true",
    matchIfMissing = true)
class SaleWindowJob {

  private static final Logger LOG = LoggerFactory.getLogger(SaleWindowJob.class);

  private final SaleWindowBoundaries boundaries;

  SaleWindowJob(SaleWindowBoundaries boundaries) {
    this.boundaries = boundaries;
  }

  @Scheduled(fixedDelayString = "${product.sale-window.interval:PT15S}")
  void run() {
    try {
      int published = boundaries.fireDue();
      if (published > 0) {
        LOG.info("Published {} price events at flash-sale window boundaries", published);
      }
    } catch (RuntimeException e) {
      LOG.error("Sale-window job failed", e);
    }
  }
}
