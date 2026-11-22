package com.mayshop.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitDefaultsTest {

  @Autowired private RateLimitProperties limits;

  @Test
  void everyLimitHasItsDocumentedDefault() {
    assertThat(limits.loginPerEmailPerMinute()).isEqualTo(5);
    assertThat(limits.loginPerIpPerMinute()).isEqualTo(60);
    assertThat(limits.refreshPerSessionPerMinute()).isEqualTo(10);
    assertThat(limits.coreRate()).isEqualTo(20);
    assertThat(limits.coreBurst()).isEqualTo(40);
  }
}
