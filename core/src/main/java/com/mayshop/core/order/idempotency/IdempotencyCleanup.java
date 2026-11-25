package com.mayshop.core.order.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Hourly job that deletes idempotency rows older than 24 hours. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class IdempotencyCleanup {

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyCleanup.class);

  private final IdempotencyStore store;

  IdempotencyCleanup(IdempotencyStore store) {
    this.store = store;
  }

  @Scheduled(fixedRateString = "${order.idempotency.cleanup-interval:PT1H}")
  void run() {
    int deleted = store.deleteExpired();
    if (deleted > 0) {
      LOG.info("Deleted {} expired idempotency rows", deleted);
    }
  }
}
