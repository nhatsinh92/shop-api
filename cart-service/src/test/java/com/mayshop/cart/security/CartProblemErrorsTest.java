package com.mayshop.cart.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProbeController.class)
class CartProblemErrorsTest {

  private static final TestJwks JWKS = new TestJwks();

  @DynamicPropertySource
  static void core(DynamicPropertyRegistry registry) {
    registry.add("CORE_URL", JWKS::coreUrl);
  }

  @AfterAll
  static void stop() {
    JWKS.close();
  }

  @Autowired private MockMvcTester mvc;

  private static String bearer() {
    return "Bearer "
        + TestJwks.token(JWKS.key, "u", "mayshop-core", "mayshop", Instant.now().plusSeconds(600));
  }

  private void assertProblem(MvcTestResult result, int status, String code) {
    assertThat(result).hasStatus(status);
    assertThat(result.getResponse().getContentType())
        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status);
    assertThat(result).bodyJson().extractingPath("$.traceId").isNotNull();
  }

  @Test
  void malformedJsonBodyIsValidationFailed() {
    assertProblem(
        mvc.post()
            .uri("/api/cart/probe")
            .header("Authorization", bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{not json")
            .exchange(),
        400,
        "VALIDATION_FAILED");
  }

  @Test
  void wrongBodyTypeIsValidationFailed() {
    assertProblem(
        mvc.post()
            .uri("/api/cart/probe")
            .header("Authorization", bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sku\":\"A\",\"quantity\":\"many\"}")
            .exchange(),
        400,
        "VALIDATION_FAILED");
  }

  @Test
  void badPathVariableIsValidationFailed() {
    assertProblem(
        mvc.get().uri("/api/cart/probe/abc").header("Authorization", bearer()).exchange(),
        400,
        "VALIDATION_FAILED");
  }

  @Test
  void redisConnectionFailureIsServiceUnavailable() {
    assertProblem(
        mvc.get().uri("/api/cart/redis-down").header("Authorization", bearer()).exchange(),
        503,
        "SERVICE_UNAVAILABLE");
  }

  @Test
  void unauthenticatedFromTheFilterChainIsAProblem() {
    assertProblem(
        mvc.get().uri("/api/cart/probe").header("Authorization", "Bearer nope").exchange(),
        401,
        "AUTH_UNAUTHENTICATED");
  }
}
