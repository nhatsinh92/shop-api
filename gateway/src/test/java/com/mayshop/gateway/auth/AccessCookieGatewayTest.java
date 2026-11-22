package com.mayshop.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.ProblemSchema;
import com.mayshop.gateway.support.StubServer;
import com.mayshop.gateway.support.Tokens;
import com.nimbusds.jose.jwk.RSAKey;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class AccessCookieGatewayTest {

  private static final StubServer CORE = new StubServer();
  private static final StubServer CART = new StubServer();
  private static final RSAKey KEY = Tokens.newKey("key-1");

  @Autowired private WebTestClient client;
  @Autowired private MeterRegistry meters;

  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) {
    registry.add("CORE_URL", CORE::url);
    registry.add("CART_SERVICE_URL", CART::url);
    registry.add("gateway.auth.jwks-min-refetch", () -> "0s");
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
    CORE.resetCounters();
    CART.resetCounters();
  }

  @Test
  void anyAuthorizationHeaderFromTheClientIsRemovedOnEveryRoute() {
    client
        .get()
        .uri("/api/cart")
        .header("Authorization", "Bearer forged")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    client
        .get()
        .uri("/api/products")
        .header("Authorization", "Bearer forged")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    assertThat(CART.lastHeaders("/api/cart")).doesNotContainKey("authorization");
    assertThat(CORE.lastHeaders("/api/products")).doesNotContainKey("authorization");
  }

  @Test
  void aValidCookieIsForwardedAsBearerToCoreAndCart() {
    String token = Tokens.valid(KEY);

    client
        .get()
        .uri("/api/products")
        .header("Cookie", "access_token=" + token)
        .header("Authorization", "Bearer forged")
        .exchange()
        .expectStatus()
        .isOk();
    client
        .get()
        .uri("/api/cart")
        .header("Cookie", "access_token=" + token)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(CORE.lastHeaders("/api/products").get("authorization"))
        .containsExactly("Bearer " + token);
    assertThat(CART.lastHeaders("/api/cart").get("authorization"))
        .containsExactly("Bearer " + token);
  }

  @Test
  void aCookieWithABadSignatureIsStrippedAndForwardedAnonymously() {
    RSAKey other = Tokens.newKey("key-1");
    String token =
        Tokens.sign(other, "key-1", Tokens.ISSUER, Tokens.AUDIENCE, Instant.now().plusSeconds(600));

    assertStripped(token, "signature");
  }

  @Test
  void aCookieWithAnUnknownKidIsStrippedAfterOneRefetch() {
    RSAKey unknown = Tokens.newKey("rotated-away");
    String token = Tokens.valid(unknown);

    assertStripped(token, "unknown_kid");
    assertThat(CORE.jwksFetches()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void aCookieWithTheWrongIssuerIsStripped() {
    String token =
        Tokens.sign(KEY, "key-1", "someone-else", Tokens.AUDIENCE, Instant.now().plusSeconds(600));

    assertStripped(token, "issuer");
  }

  @Test
  void aCookieWithTheWrongAudienceIsStripped() {
    String token =
        Tokens.sign(KEY, "key-1", Tokens.ISSUER, "other-app", Instant.now().plusSeconds(600));

    assertStripped(token, "audience");
  }

  @Test
  void anExpiredCookieIsStripped() {
    String token =
        Tokens.sign(KEY, "key-1", Tokens.ISSUER, Tokens.AUDIENCE, Instant.now().minusSeconds(30));

    assertStripped(token, "expired");
  }

  @Test
  void aCookieThatIsNotAJwtIsStripped() {
    assertStripped("not-a-jwt", "malformed");
  }

  @Test
  void aStrippedTokenIsCountedAndLoggedWithoutTheToken(CapturedOutput output) {
    String token = "garbage.token.value";
    double before = counter("malformed");

    client
        .get()
        .uri("/api/products")
        .header("Cookie", "access_token=" + token)
        .exchange()
        .expectStatus()
        .isUnauthorized();

    assertThat(counter("malformed")).isEqualTo(before + 1);
    assertThat(output.getAll()).contains("WARN").contains("reason=malformed").doesNotContain(token);
  }

  @Test
  void aStrippedRequestReachesAProtectedServiceAsAnonymousAndTheClientSeesItsProblem() {
    RSAKey other = Tokens.newKey("key-1");
    String token =
        Tokens.sign(other, "key-1", Tokens.ISSUER, Tokens.AUDIENCE, Instant.now().plusSeconds(600));

    String body =
        client
            .get()
            .uri("/api/auth/me")
            .header("Cookie", "access_token=" + token)
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectHeader()
            .contentType("application/problem+json")
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertThat(body).contains("AUTH_UNAUTHENTICATED");
    assertThat(CORE.lastHeaders("/api/auth/me")).doesNotContainKey("authorization");
  }

  @Test
  void otherCookiesAreForwardedUnchangedWhenTheAccessCookieIsStripped() {
    client
        .get()
        .uri("/api/products")
        .header("Cookie", "refresh_token=r1; access_token=bad; logged_in=1")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    assertThat(CORE.lastHeaders("/api/products").get("cookie"))
        .containsExactly("refresh_token=r1; logged_in=1");
  }

  @Test
  void twoAccessCookiesAreTreatedAsMalformedAndStripped() {
    String token = Tokens.valid(KEY);

    client
        .get()
        .uri("/api/products")
        .header("Cookie", "access_token=" + token + "; access_token=" + token)
        .exchange()
        .expectStatus()
        .isUnauthorized();

    assertThat(CORE.lastHeaders("/api/products"))
        .doesNotContainKey("authorization")
        .doesNotContainKey("cookie");
  }

  @Test
  void loginRefreshAndLogoutKeepTheirCookiesAndDropOnlyAuthorization() {
    for (String path : List.of("/api/auth/login", "/api/auth/refresh", "/api/auth/logout")) {
      client
          .post()
          .uri(path)
          .header("Cookie", "access_token=expired.jwt.value; refresh_token=r1")
          .header("Authorization", "Bearer forged")
          .exchange()
          .expectStatus()
          .isUnauthorized();

      Map<String, List<String>> seen = CORE.lastHeaders(path);
      assertThat(seen).doesNotContainKey("authorization");
      assertThat(seen.get("cookie"))
          .containsExactly("access_token=expired.jwt.value; refresh_token=r1");
    }
    assertThat(CORE.jwksFetches()).isZero();
  }

  @Test
  void wellKnownIsNotRoutedAndAnswers404() {
    String body =
        client
            .get()
            .uri("/.well-known/jwks.json")
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertThat(ProblemSchema.violations(body)).isEmpty();
    assertThat(CORE.jwksFetches()).isZero();
  }

  private void assertStripped(String token, String reason) {
    double before = counter(reason);

    client
        .get()
        .uri("/api/products")
        .header("Cookie", "access_token=" + token + "; logged_in=1")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    Map<String, List<String>> seen = CORE.lastHeaders("/api/products");
    assertThat(seen).doesNotContainKey("authorization");
    assertThat(seen.get("cookie")).containsExactly("logged_in=1");
    assertThat(counter(reason)).isEqualTo(before + 1);
  }

  private double counter(String reason) {
    var counter = meters.find("gateway.auth.token.invalid").tag("reason", reason).counter();
    return counter == null ? 0 : counter.count();
  }
}
