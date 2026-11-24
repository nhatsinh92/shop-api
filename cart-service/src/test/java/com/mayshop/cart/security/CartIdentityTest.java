package com.mayshop.cart.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.cart.contract.ContractValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import({ProbeController.class, CartIdentityTest.Meters.class})
class CartIdentityTest {

  private static final TestJwks JWKS = new TestJwks();
  private static final String VALID_GUEST = "AAAAAAAAAAAAAAAAAAAAAA";

  @TestConfiguration
  static class Meters {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @DynamicPropertySource
  static void core(DynamicPropertyRegistry registry) {
    registry.add("CORE_URL", JWKS::coreUrl);
  }

  @AfterAll
  static void stop() {
    JWKS.close();
  }

  @Autowired private MockMvcTester mvc;
  @Autowired private MeterRegistry meters;

  private static String valid(String subject) {
    return TestJwks.token(
        JWKS.key, subject, "mayshop-core", "mayshop", Instant.now().plusSeconds(600));
  }

  private MvcTestResult get(String bearer, Cookie... cookies) {
    var request = mvc.get().uri("/api/cart/probe");
    if (bearer != null) {
      request = request.header("Authorization", "Bearer " + bearer);
    }
    if (cookies.length > 0) {
      request = request.cookie(cookies);
    }
    return request.exchange();
  }

  private void assertUnauthenticated(MvcTestResult result) {
    assertThat(result).hasStatus(401);
    MockHttpServletResponse response = result.getResponse();
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTH_UNAUTHENTICATED");
    assertThat(result).bodyJson().extractingPath("$.traceId").isNotNull();
  }

  @Test
  void validTokenResolvesToUserWithSubAsId() {
    assertThat(get(valid("user-42"))).hasStatusOk().bodyText().isEqualTo("user:user-42:null");
  }

  @Test
  void tokenSignedWithAnotherKeyIsRejected() {
    String token =
        TestJwks.token(
            TestJwks.generate("test-key"),
            "u",
            "mayshop-core",
            "mayshop",
            Instant.now().plusSeconds(600));
    assertUnauthenticated(get(token));
  }

  @Test
  void tokenWithWrongIssuerIsRejected() {
    String token =
        TestJwks.token(JWKS.key, "u", "someone-else", "mayshop", Instant.now().plusSeconds(600));
    assertUnauthenticated(get(token));
  }

  @Test
  void tokenWithWrongAudienceIsRejected() {
    String token =
        TestJwks.token(JWKS.key, "u", "mayshop-core", "other", Instant.now().plusSeconds(600));
    assertUnauthenticated(get(token));
  }

  @Test
  void expiredTokenIsRejected() {
    String token =
        TestJwks.token(JWKS.key, "u", "mayshop-core", "mayshop", Instant.now().minusSeconds(600));
    assertUnauthenticated(get(token));
  }

  @Test
  void garbageBearerIsRejected() {
    assertUnauthenticated(get("not-a-jwt"));
  }

  @Test
  void noBearerAndNoHintIsGuestWithoutId() {
    assertThat(get(null)).hasStatusOk().bodyText().isEqualTo("guest:null");
  }

  @Test
  void noBearerWithHintCookieIsUnauthenticatedAndCreatesNoGuestId() {
    MvcTestResult result = get(null, new Cookie("logged_in", "1"));
    assertUnauthenticated(result);
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
  }

  @Test
  void hintCookieOnEveryCartRouteIsUnauthenticated() {
    Cookie hint = new Cookie("logged_in", "1");
    assertUnauthenticated(mvc.get().uri("/api/cart").cookie(hint).exchange());
    assertUnauthenticated(mvc.get().uri("/api/cart/summary").cookie(hint).exchange());
    assertUnauthenticated(mvc.post().uri("/api/cart/items").cookie(hint).exchange());
    assertUnauthenticated(mvc.put().uri("/api/cart/items/SKU-1").cookie(hint).exchange());
    assertUnauthenticated(mvc.delete().uri("/api/cart/items/SKU-1").cookie(hint).exchange());
    assertUnauthenticated(mvc.post().uri("/api/cart/merge").cookie(hint).exchange());
  }

  @Test
  void validBearerWithHintCookieIsUser() {
    assertThat(get(valid("user-7"), new Cookie("logged_in", "1")))
        .hasStatusOk()
        .bodyText()
        .isEqualTo("user:user-7:null");
  }

  @Test
  void hintCookieWithOtherValueIsNotTheHint() {
    assertThat(get(null, new Cookie("logged_in", "0"))).hasStatusOk();
  }

  @Test
  void validGuestCookieIsTheGuestId() {
    assertThat(get(null, new Cookie("guest_cart", VALID_GUEST)))
        .hasStatusOk()
        .bodyText()
        .isEqualTo("guest:" + VALID_GUEST);
  }

  @Test
  void userKeepsTheValidGuestCookieForMerge() {
    assertThat(get(valid("u1"), new Cookie("guest_cart", VALID_GUEST)))
        .hasStatusOk()
        .bodyText()
        .isEqualTo("user:u1:" + VALID_GUEST);
  }

  @Test
  void invalidGuestCookiesAreIgnoredAndCounted() {
    double before = meters.counter("cart.guest_cookie.invalid").count();
    String[] invalid = {
      "AAAAAAAAAAAAAAAAAAAAA", // 21 characters, too short
      "AAAAAAAAAAAAAAAAAAAAAAA", // 23 characters, too long
      "AAAAAAAAAAAAAAAAAAAA=!", // wrong characters
      "AAAAAAAAAAAAAAAAAAAA==", // padding is not allowed
      ""
    };
    for (String value : invalid) {
      assertThat(get(null, new Cookie("guest_cart", value)))
          .hasStatusOk()
          .bodyText()
          .isEqualTo("guest:null");
    }
    assertThat(meters.counter("cart.guest_cookie.invalid").count() - before)
        .isEqualTo(invalid.length);
  }

  @Test
  void healthEndpointsArePublic() {
    assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatusOk();
  }

  @Test
  void unauthenticatedGetCartMatchesTheContract() {
    MvcTestResult result =
        mvc.get().uri("/api/cart").cookie(new Cookie("logged_in", "1")).exchange();
    ContractValidator.cart().assertValid(result.getResponse(), "getCart");
    assertThat(result.getResponse().getContentType())
        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
  }
}
