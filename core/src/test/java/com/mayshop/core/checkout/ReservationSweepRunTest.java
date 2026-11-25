package com.mayshop.core.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

/** The scheduled entry point of {@link ReservationSweep}, without a database. */
class ReservationSweepRunTest {

  private static final Instant NOW = Instant.parse("2026-12-12T01:00:00Z");

  private final Reservations reservations = mock(Reservations.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private ReservationSweep sweep() {
    ObjectProvider<Clock> clock = mock();
    when(clock.getIfAvailable(any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
    return new ReservationSweep(reservations, clock, meters);
  }

  private double expired() {
    return meters.get("checkout.reservations.expired").counter().count();
  }

  @Test
  void runIncrementsTheExpiredCounterByTheNumberOfExpiredReservations() {
    when(reservations.expireDue(NOW, ReservationSweep.BATCH)).thenReturn(7);
    ReservationSweep sweep = sweep();

    sweep.run();
    assertThat(expired()).isEqualTo(7);
    sweep.run();
    assertThat(expired()).isEqualTo(14);
    verify(reservations, org.mockito.Mockito.times(2)).expireDue(NOW, 100);
  }

  @Test
  void runLeavesTheCounterAloneWhenNothingIsDue() {
    when(reservations.expireDue(any(), anyInt())).thenReturn(0);

    sweep().run();

    assertThat(expired()).isZero();
  }

  @Test
  void runSwallowsAFailureSoTheScheduleKeepsGoing() {
    when(reservations.expireDue(any(), anyInt())).thenThrow(new IllegalStateException("down"));

    assertThatCode(() -> sweep().run()).doesNotThrowAnyException();
    assertThat(expired()).isZero();
  }

  @Test
  void theScheduleDefaultsToTenSecondsAndReadsTheSweepIntervalProperty() throws Exception {
    Method run = ReservationSweep.class.getDeclaredMethod("run");
    Scheduled scheduled = run.getAnnotation(Scheduled.class);

    assertThat(scheduled.fixedDelayString()).isEqualTo("${checkout.sweep.interval:PT10S}");
    assertThat(Duration.parse("PT10S")).isEqualTo(Duration.ofSeconds(10));
  }
}
