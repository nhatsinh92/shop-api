package com.mayshop.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.InMemoryRateLimiter;
import com.mayshop.gateway.support.ProblemSchema;
import com.mayshop.gateway.support.StubServer;
import com.mayshop.gateway.support.Tokens;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RateLimitGatewayTest {

  private static final StubServer CORE = new StubServer();
  private static final StubServer CART = new StubServer();
  private static final RSAKey KEY = Tokens.newKey("key-1");

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

  @BeforeAll
  static void serveKeys() {
    CORE.serveKeys(Tokens.publicSet(KEY));
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
    WebTestClient.RequestBodySpec spec = client.post().uri("/api/auth/login");
    if (forwardedFor != null) {
      spec.header("X-Forwarded-For", forwardedFor);
    }
    return spec.header("Content-Type", "application/json")
        .bodyValue("{\"email\":\"" + email + "\",\"password\":\"x\"}")
        .exchange()
        .expectBody()
        .returnResult()
        .getStatus()
        .value();
  }

  @Test
  void theSixthLoginForOneEmailIsLimitedWhileAnotherEmailFromTheSameIpSucceeds() {
    for (int i = 0; i < 5; i++) {
      assertThat(login("a@example.com", null)).isNotEqualTo(429);
    }
    assertThat(login("a@example.com", null)).isEqualTo(429);
    assertThat(login("  A@Example.COM ", null)).as("normalised email").isEqualTo(429);
    assertThat(login("b@example.com", null)).isNotEqualTo(429);
  }

  @Test
  void the61stLoginFromOneIpIsLimited() {
    for (int i = 0; i < 60; i++) {
      assertThat(login("user" + i + "@example.com", null)).isNotEqualTo(429);
    }
    assertThat(login("another@example.com", null)).isEqualTo(429);
  }

  @Test
  void aSpoofedXForwardedForDoesNotGiveANewBucket() {
    for (int i = 0; i < 60; i++) {
      login("user" + i + "@example.com", "10.0.0." + i);
    }
    assertThat(login("another@example.com", "203.0.113.99")).isEqualTo(429);
  }

  @Test
  void refreshIsLimitedPerHashedRefreshCookieAndNotPerIp() {
    for (int i = 0; i < 10; i++) {
      assertThat(refresh("session-1")).isNotEqualTo(429);
    }
    assertThat(refresh("session-1")).isEqualTo(429);
    assertThat(refresh("session-2")).isNotEqualTo(429);
    assertThat(limiter.keysStartingWith("login:ip:")).isZero();
  }

  @Test
  void aRefreshWithoutTheCookieIsNotLimitedAndReachesCore() {
    for (int i = 0; i < 30; i++) {
      assertThat(
              client
                  .post()
                  .uri("/api/auth/refresh")
                  .exchange()
                  .expectBody()
                  .returnResult()
                  .getStatus()
                  .value())
          .isEqualTo(401);
    }
  }

  private int refresh(String token) {
    return client
        .post()
        .uri("/api/auth/refresh")
        .header("Cookie", "refresh_token=" + token)
        .exchange()
        .expectBody()
        .returnResult()
        .getStatus()
        .value();
  }

  @Test
  void twoUsersBehindTheSameIpHaveSeparateCoreBuckets() {
    String first = Tokens.valid(KEY);
    String second = Tokens.valid(KEY);
    for (int i = 0; i < 40; i++) {
      assertThat(products(first)).isEqualTo(200);
    }
    assertThat(products(first)).isEqualTo(429);
    assertThat(products(second)).isEqualTo(200);
  }

  @Test
  void anonymousCoreRequestsAreKeyedByClientIp() {
    for (int i = 0; i < 40; i++) {
      assertThat(anonymousProducts()).isNotEqualTo(429);
    }
    assertThat(anonymousProducts()).isEqualTo(429);
  }

  private int products(String token) {
    return client
        .get()
        .uri("/api/products")
        .header("Cookie", "access_token=" + token)
        .exchange()
        .expectBody()
        .returnResult()
        .getStatus()
        .value();
  }

  private int anonymousProducts() {
    return client
        .get()
        .uri("/api/products")
        .exchange()
        .expectBody()
        .returnResult()
        .getStatus()
        .value();
  }

  private int cart(String token) {
    WebTestClient.RequestHeadersSpec<?> spec = client.get().uri("/api/cart");
    if (token != null) {
      spec = client.get().uri("/api/cart").header("Cookie", "access_token=" + token);
    }
    return spec.exchange().expectBody().returnResult().getStatus().value();
  }

  @Test
  void cartRoutesAreNeverAnsweredWith429() {
    String token = Tokens.valid(KEY);
    for (int i = 0; i < 700; i++) {
      assertThat(cart(null)).isEqualTo(401);
      assertThat(cart(token)).isEqualTo(200);
    }
    assertThat(limiter.keysStartingWith("")).isZero();
  }

  @Test
  void anExceededLimitIsAProblemWithRetryAfter() {
    for (int i = 0; i < 5; i++) {
      login("p@example.com", null);
    }
    EntityExchangeResult<String> result =
        client
            .post()
            .uri("/api/auth/login")
            .header("Content-Type", "application/json")
            .bodyValue("{\"email\":\"p@example.com\",\"password\":\"x\"}")
            .exchange()
            .expectStatus()
            .isEqualTo(429)
            .expectHeader()
            .contentType("application/problem+json")
            .expectHeader()
            .valueEquals("Retry-After", "30")
            .expectBody(String.class)
            .returnResult();
    String body = result.getResponseBody();
    assertThat(ProblemSchema.violations(body)).isEmpty();
    assertThat(body).contains("\"code\":\"RATE_LIMITED\"").contains("\"traceId\"");
  }

  @Test
  void aTokenWithTheWrongIssuerIsKeyedByIpNotBySubject() {
    String bad =
        Tokens.sign(KEY, "key-1", "someone-else", Tokens.AUDIENCE, Instant.now().plusSeconds(600));
    products(bad);
    assertThat(limiter.keysStartingWith("core:ip:")).isEqualTo(1);
    assertThat(limiter.keysStartingWith("core:sub:")).isZero();
  }
}
