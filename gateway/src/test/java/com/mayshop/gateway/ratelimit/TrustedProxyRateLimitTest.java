package com.mayshop.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.InMemoryRateLimiter;
import com.mayshop.gateway.support.StubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The test client connects from loopback, which this test configures as the trusted proxy. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "gateway.client-ip.trusted-proxies=127.0.0.0/8,::1/128")
@AutoConfigureWebTestClient
class TrustedProxyRateLimitTest {

  private static final StubServer CORE = new StubServer();
  private static final StubServer CART = new StubServer();

  @TestConfiguration(proxyBeanMethods = false)
  static class Config {
    @Bean
    @Primary
    InMemoryRateLimiter inMemoryRateLimiter() {
      return new InMemoryRateLimiter();
    }
  }

  @Autowired private WebTestClient client;
  @Autowired private InMemoryRateLimiter limiter;

  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) {
    registry.add("CORE_URL", CORE::url);
    registry.add("CART_SERVICE_URL", CART::url);
  }

  @AfterAll
  static void stop() {
    CORE.close();
    CART.close();
  }

  @BeforeEach
  void reset() {
    limiter.reset();
  }

  private int login(String email, String forwardedFor) {
    return client
        .post()
        .uri("/api/auth/login")
        .header("X-Forwarded-For", forwardedFor)
        .header("Content-Type", "application/json")
        .bodyValue("{\"email\":\"" + email + "\",\"password\":\"x\"}")
        .exchange()
        .expectBody()
        .returnResult()
        .getStatus()
        .value();
  }

  @Test
  void twoClientIpsBehindTheSameTrustedProxyHaveSeparateLoginBudgets() {
    for (int i = 0; i < 60; i++) {
      assertThat(login("user" + i + "@example.com", "203.0.113.1")).isNotEqualTo(429);
    }
    assertThat(login("one@example.com", "203.0.113.1")).isEqualTo(429);
    assertThat(login("two@example.com", "203.0.113.2")).isNotEqualTo(429);
  }

  @Test
  void aClientPrependingFakeEntriesCannotPickItsLoginKey() {
    for (int i = 0; i < 60; i++) {
      login("user" + i + "@example.com", "10.0.0." + i + ", 203.0.113.1");
    }
    assertThat(login("another@example.com", "1.1.1.1, 203.0.113.1")).isEqualTo(429);
  }

  @Test
  void anonymousCoreRequestsAreKeyedByTheForwardedClientIp() {
    for (int i = 0; i < 40; i++) {
      get("203.0.113.1");
    }
    assertThat(get("203.0.113.1")).isEqualTo(429);
    assertThat(get("203.0.113.2")).isNotEqualTo(429);
  }

  private int get(String forwardedFor) {
    return client
        .get()
        .uri("/api/products")
        .header("X-Forwarded-For", forwardedFor)
        .exchange()
        .expectBody()
        .returnResult()
        .getStatus()
        .value();
  }
}
