package com.mayshop.core.order.internal;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs {@link OrderRecovery} every {@code order.recovery.interval} (default 30 s); {@code
 * order.recovery.enabled=false} turns it off, for tests.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "order.recovery.enabled", havingValue = "true", matchIfMissing = true)
class OrderRecoveryJob {

  private static final Logger LOG = LoggerFactory.getLogger(OrderRecoveryJob.class);

  private final OrderRecovery recovery;
  private final Clock clock;

  OrderRecoveryJob(OrderRecovery recovery, Clock clock) {
    this.recovery = recovery;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${order.recovery.interval:PT30S}",
      initialDelayString = "${order.recovery.interval:PT30S}")
  void run() {
    try {
      int count = recovery.recoverStale(Instant.now(clock), OrderRecovery.BATCH);
      if (count > 0) {
        LOG.info("Recovered {} orders with an unresolved payment", count);
      }
    } catch (RuntimeException e) {
      LOG.error("Order recovery failed", e);
    }
  }
}
