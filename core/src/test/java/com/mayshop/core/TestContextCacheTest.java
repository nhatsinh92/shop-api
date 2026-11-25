package com.mayshop.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.SpringProperties;

/** The test-only {@code spring.properties} keeps the Spring test context cache small. */
class TestContextCacheTest {

  @Test
  void theTestContextCacheHoldsAtMostOneContext() {
    assertThat(SpringProperties.getProperty("spring.test.context.cache.maxSize")).isEqualTo("1");
  }
}
