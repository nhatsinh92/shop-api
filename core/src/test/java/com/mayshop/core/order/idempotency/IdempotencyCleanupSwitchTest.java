package com.mayshop.core.order.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code order.idempotency.cleanup.enabled} switches the cleanup; it is on when the property is
 * missing.
 */
class IdempotencyCleanupSwitchTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(IdempotencyStore.class, () -> mock(IdempotencyStore.class))
          .withUserConfiguration(IdempotencyCleanup.class);

  @Test
  void theCleanupRunsByDefault() {
    runner.run(context -> assertThat(context).hasSingleBean(IdempotencyCleanup.class));
  }

  @Test
  void theCleanupIsOffWhenDisabled() {
    runner
        .withPropertyValues("order.idempotency.cleanup.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(IdempotencyCleanup.class));
  }
}
