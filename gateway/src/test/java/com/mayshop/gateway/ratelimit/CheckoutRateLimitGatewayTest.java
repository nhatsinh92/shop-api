package com.mayshop.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.InMemoryRateLimiter;
import com.mayshop.gateway.support.ProblemSchema;
import com.mayshop.gateway.support.StubServer;
import com.mayshop.gateway.support.Tokens;
import com.nimbusds.jose.jwk.RSAKey;
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
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class CheckoutRateLimitGatewayTest {

  private static final String RESERVATIONS = "/api/checkout/reservations";
  private static final String ORDERS = "/api/orders";
  private static final CheckoutStub CORE = new CheckoutStub();
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
  @Autowired private RateLimitProperties limits;

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
    CORE.reset();
  }

  private EntityExchangeResult<byte[]> post(String path, String token, String idempotencyKey) {
    WebTestClient.RequestBodySpec spec =
        client.post().uri(path).header("Cookie", "access_token=" + token);
    if (idempotencyKey != null) {
      spec.header("Idempotency-Key", idempotencyKey);
    }
    return spec.exchange().expectBody().returnResult();
  }

  private int status(String path, String token) {
    return post(path, token, null).getStatus().value();
  }

  @Test
  void theCheckoutLimitsHaveTheirDocumentedDefaults() {
    assertThat(limits.checkoutReservationRate()).isEqualTo(10);
    assertThat(limits.orderRate()).isEqualTo(20);
  }

  @Test
  void theEleventhReservationInAMinuteIsLimitedAndTheOtherUserIsNot() {
    String user = Tokens.valid(KEY);
    for (int i = 0; i < 10; i++) {
      assertThat(status(RESERVATIONS, user)).isEqualTo(200);
    }
    assertThat(status(RESERVATIONS, user)).isEqualTo(429);
    assertThat(status(RESERVATIONS, Tokens.valid(KEY))).as("second user").isEqualTo(200);
  }

  @Test
  void theTwentyFirstOrderInAMinuteIsLimitedAndTheOtherUserIsNot() {
    String user = Tokens.valid(KEY);
    for (int i = 0; i < 20; i++) {
      assertThat(status(ORDERS, user)).isEqualTo(200);
    }
    assertThat(status(ORDERS, user)).isEqualTo(429);
    assertThat(status(ORDERS, Tokens.valid(KEY))).as("second user").isEqualTo(200);
  }

  @Test
  void anExceededReservationLimitIsAnInstructiveProblemWithRetryAfter() {
    assertExceededIsAProblem(RESERVATIONS, 10);
  }

  @Test
  void anExceededOrderLimitIsAnInstructiveProblemWithRetryAfter() {
    assertExceededIsAProblem(ORDERS, 20);
  }

  private void assertExceededIsAProblem(String path, int allowed) {
    String user = Tokens.valid(KEY);
    for (int i = 0; i < allowed; i++) {
      post(path, user, null);
    }
    EntityExchangeResult<String> result =
        client
            .post()
            .uri(path)
            .header("Cookie", "access_token=" + user)
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
    assertThat(body).contains("\"code\":\"RATE_LIMITED\"");
  }

  @Test
  void aFailedAttemptFiveRetriesWithOneKeyAndACodAttemptInAMinuteGetNo429() {
    String user = Tokens.valid(KEY);
    assertThat(status(RESERVATIONS, user)).isEqualTo(200);
    assertThat(status(ORDERS, user)).as("failed attempt").isEqualTo(200);
    for (int i = 0; i < 5; i++) {
      assertThat(post(ORDERS, user, "same-key").getStatus().value())
          .as("retry " + i)
          .isEqualTo(200);
    }
    assertThat(status(ORDERS, user)).as("COD attempt").isEqualTo(200);
  }

  @Test
  void getCheckoutAndOrderOperationsAreNotCountedByTheCheckoutLimits() {
    String user = Tokens.valid(KEY);
    for (int i = 0; i < 12; i++) {
      for (String path : new String[] {RESERVATIONS + "/abc", ORDERS, ORDERS + "/abc"}) {
        assertThat(
                client
                    .method(HttpMethod.GET)
                    .uri(path)
                    .header("Cookie", "access_token=" + user)
                    .exchange()
                    .expectBody()
                    .returnResult()
                    .getStatus()
                    .value())
            .isEqualTo(200);
      }
    }
    assertThat(limiter.keysStartingWith("checkout:")).isZero();
  }

  @Test
  void theCheckoutLimitSitsOnTopOfTheCoreBucketAndIsKeyedBySub() {
    status(RESERVATIONS, Tokens.valid(KEY));
    assertThat(limiter.keysStartingWith("core:sub:")).isEqualTo(1);
    assertThat(limiter.keysStartingWith("checkout:reservation:sub:")).isEqualTo(1);
    assertThat(limiter.keysStartingWith("core:ip:")).isZero();
  }

  @Test
  void idempotencyKeyAndMockPaymentOutcomeReachCoreUnchangedAndReplayedReachesTheClient() {
    String user = Tokens.valid(KEY);
    EntityExchangeResult<byte[]> result =
        client
            .post()
            .uri(ORDERS)
            .header("Cookie", "access_token=" + user)
            .header("Idempotency-Key", "attempt-one")
            .header("X-Mock-Payment-Outcome", "DECLINED")
            .exchange()
            .expectBody()
            .returnResult();
    assertThat(CORE.lastHeaders().get("idempotency-key"))
        .containsExactly("attempt-one");
    assertThat(CORE.lastHeaders().get("x-mock-payment-outcome")).containsExactly("DECLINED");
    assertThat(result.getResponseHeaders().get("Idempotent-Replayed")).containsExactly("true");
  }

  @Test
  void aForeignOriginIsForbiddenOnBothCheckoutPostsBeforeAnyLimitOrUpstream() {
    String user = Tokens.valid(KEY);
    for (String path : new String[] {ORDERS, RESERVATIONS}) {
      for (String origin : new String[] {"https://evil.example", "null"}) {
        client
            .post()
            .uri(path)
            .header("Cookie", "access_token=" + user)
            .header("Origin", origin)
            .exchange()
            .expectStatus()
            .isForbidden();
      }
    }
    assertThat(CORE.requests()).isZero();
    assertThat(limiter.keysStartingWith("")).isZero();
  }

  @Test
  void cartRoutesStillHaveNoRateLimit() {
    String user = Tokens.valid(KEY);
    for (int i = 0; i < 50; i++) {
      client.get().uri("/api/cart").header("Cookie", "access_token=" + user).exchange();
    }
    assertThat(limiter.keysStartingWith("")).isZero();
  }
}
