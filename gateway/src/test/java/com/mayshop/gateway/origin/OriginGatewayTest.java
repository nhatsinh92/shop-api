package com.mayshop.gateway.origin;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.InMemoryRateLimiter;
import com.mayshop.gateway.support.ProblemSchema;
import com.mayshop.gateway.support.StubServer;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
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
class OriginGatewayTest {

  private static final String WEB = "http://localhost:3000";
  private static final StubServer CORE = new StubServer();
  private static final StubServer CART = new StubServer();
  private static final List<String> BAD_ORIGINS =
      List.of("https://evil.example", "null", "http://localhost:3001", "https://localhost:3000");
  private static final List<String> PATHS = List.of("/api/auth/login", "/api/cart/items");
  private static final List<HttpMethod> STATE_CHANGING =
      List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

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
    CORE.resetCounters();
    CART.resetCounters();
  }

  private static StubServer upstream(String path) {
    return path.startsWith("/api/cart") ? CART : CORE;
  }

  private EntityExchangeResult<byte[]> send(
      HttpMethod method, String path, String origin, String fetchSite) {
    WebTestClient.RequestBodySpec spec = client.method(method).uri(path);
    if (origin != null) {
      spec.header("Origin", origin);
    }
    if (fetchSite != null) {
      spec.header("Sec-Fetch-Site", fetchSite);
    }
    return spec.exchange().expectBody().returnResult();
  }

  @Test
  void preflightFromTheWebOriginSucceedsWithCredentials() {
    EntityExchangeResult<byte[]> result =
        client
            .options()
            .uri("/api/cart/items")
            .header("Origin", WEB)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type")
            .exchange()
            .expectBody()
            .returnResult();
    assertThat(result.getStatus().is2xxSuccessful()).isTrue();
    assertThat(result.getResponseHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(WEB);
    assertThat(result.getResponseHeaders().getFirst("Access-Control-Allow-Credentials"))
        .isEqualTo("true");
    assertThat(result.getResponseHeaders().getFirst("Access-Control-Allow-Methods"))
        .contains("POST");
    assertThat(CART.lastHeaders("/api/cart/items")).isEmpty();
  }

  @Test
  void preflightAndRequestsFromOtherOriginsGetNoAllowOriginHeader() {
    for (String origin : BAD_ORIGINS) {
      EntityExchangeResult<byte[]> preflight =
          client
              .options()
              .uri("/api/auth/login")
              .header("Origin", origin)
              .header("Access-Control-Request-Method", "POST")
              .exchange()
              .expectBody()
              .returnResult();
      assertThat(preflight.getResponseHeaders().containsHeader("Access-Control-Allow-Origin"))
          .as("preflight " + origin)
          .isFalse();
      EntityExchangeResult<byte[]> get = send(HttpMethod.GET, "/api/products", origin, null);
      assertThat(get.getResponseHeaders().containsHeader("Access-Control-Allow-Origin"))
          .as("GET " + origin)
          .isFalse();
      EntityExchangeResult<byte[]> post = send(HttpMethod.POST, "/api/auth/login", origin, null);
      assertThat(post.getResponseHeaders().containsHeader("Access-Control-Allow-Origin"))
          .as("POST " + origin)
          .isFalse();
    }
  }

  @Test
  void aResponseToTheWebOriginCarriesAllowOriginAndCredentialsAndNeverAWildcard() {
    EntityExchangeResult<byte[]> result = send(HttpMethod.GET, "/api/products", WEB, null);
    assertThat(result.getResponseHeaders().get("Access-Control-Allow-Origin")).containsExactly(WEB);
    assertThat(result.getResponseHeaders().getFirst("Access-Control-Allow-Credentials"))
        .isEqualTo("true");
  }

  @Test
  void stateChangingRequestsFromAnotherOriginAreForbiddenAndNeverReachTheUpstream() {
    for (String path : PATHS) {
      for (HttpMethod method : STATE_CHANGING) {
        for (String origin : BAD_ORIGINS) {
          EntityExchangeResult<byte[]> result = send(method, path, origin, null);
          String label = method + " " + path + " from " + origin;
          assertThat(result.getStatus().value()).as(label).isEqualTo(403);
          assertThat(result.getResponseHeaders().getContentType().toString())
              .as(label)
              .startsWith("application/problem+json");
          String body = new String(result.getResponseBody());
          assertThat(body).as(label).contains("\"code\":\"AUTH_FORBIDDEN\"").contains("traceId");
          assertThat(ProblemSchema.violations(body)).as(label).isEmpty();
          assertThat(upstream(path).lastHeaders(path)).as(label).isEmpty();
        }
      }
    }
  }

  @Test
  void stateChangingRequestsFromTheWebOriginReachTheUpstream() {
    for (String path : PATHS) {
      for (HttpMethod method : STATE_CHANGING) {
        EntityExchangeResult<byte[]> result = send(method, path, WEB, null);
        assertThat(result.getStatus().value()).as(method + " " + path).isNotEqualTo(403);
        assertThat(upstream(path).lastHeaders(path)).as(method + " " + path).isNotEmpty();
      }
    }
  }

  @Test
  void withoutOriginAStateChangingRequestIsForbiddenOnlyWhenFetchSiteIsCrossSite() {
    for (String path : PATHS) {
      for (HttpMethod method : STATE_CHANGING) {
        CORE.resetCounters();
        CART.resetCounters();
        EntityExchangeResult<byte[]> blocked = send(method, path, null, "cross-site");
        assertThat(blocked.getStatus().value()).as(method + " " + path).isEqualTo(403);
        assertThat(upstream(path).lastHeaders(path)).isEmpty();
        for (String site : new String[] {null, "same-origin", "same-site", "none"}) {
          EntityExchangeResult<byte[]> ok = send(method, path, null, site);
          assertThat(ok.getStatus().value()).as(method + " " + path + " " + site).isNotEqualTo(403);
        }
      }
    }
  }

  @Test
  void getHeadAndOptionsAreNeverRejectedByTheOriginCheck() {
    for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)) {
      for (String origin : BAD_ORIGINS) {
        assertThat(send(method, "/api/products", origin, "cross-site").getStatus().value())
            .as(method + " " + origin)
            .isNotEqualTo(403);
      }
    }
  }

  @Test
  void aRejectedRequestDoesNotConsumeRateLimitTokens() {
    for (int i = 0; i < 20; i++) {
      assertThat(
              send(HttpMethod.POST, "/api/auth/login", "https://evil.example", null)
                  .getStatus()
                  .value())
          .isEqualTo(403);
    }
    assertThat(send(HttpMethod.POST, "/api/auth/login", WEB, null).getStatus().value())
        .isNotEqualTo(429)
        .isNotEqualTo(403);
  }
}
