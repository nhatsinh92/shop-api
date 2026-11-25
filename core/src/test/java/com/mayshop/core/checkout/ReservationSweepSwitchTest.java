package com.mayshop.core.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** {@code checkout.sweep.enabled} switches the sweep; it is on when the property is missing. */
class ReservationSweepSwitchTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(Reservations.class, () -> mock(Reservations.class))
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withUserConfiguration(ReservationSweep.class);

  @Test
  void theSweepRunsByDefault() {
    runner.run(context -> assertThat(context).hasSingleBean(ReservationSweep.class));
  }

  @Test
  void theSweepIsOffWhenDisabled() {
    runner
        .withPropertyValues("checkout.sweep.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ReservationSweep.class));
  }
}
