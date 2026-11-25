package com.mayshop.core.checkout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Gives the stock of expired reservations back. Runs every {@code checkout.sweep.interval} ({@code
 * CHECKOUT_SWEEP_INTERVAL}, default 10 s); {@code checkout.sweep.enabled=false} turns it off, for
 * tests.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "checkout.sweep.enabled", havingValue = "true", matchIfMissing = true)
class ReservationSweep {

  private static final Logger LOG = LoggerFactory.getLogger(ReservationSweep.class);

  /** Rows taken per run. */
  static final int BATCH = 100;

  private final Reservations reservations;
  private final Clock clock;
  private final Counter expired;

  ReservationSweep(Reservations reservations, ObjectProvider<Clock> clock, MeterRegistry meters) {
    this.reservations = reservations;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
    this.expired = Counter.builder("checkout.reservations.expired").register(meters);
  }

  @Scheduled(fixedDelayString = "${checkout.sweep.interval:PT10S}")
  void run() {
    try {
      int count = reservations.expireDue(Instant.now(clock), BATCH);
      if (count > 0) {
        expired.increment(count);
        LOG.info("Expired {} reservations", count);
      }
    } catch (RuntimeException e) {
      LOG.error("Reservation sweep failed", e);
    }
  }
}
