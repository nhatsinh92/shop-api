package com.mayshop.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.InMemoryRateLimiter;
import com.mayshop.gateway.support.ProblemSchema;
import com.mayshop.gateway.support.StubServer;
import com.mayshop.gateway.support.Tokens;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
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

/** The cart route passes the cart cookies through as cart-service needs them. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class CartRouteGatewayTest {

  private static final String WEB = "http://localhost:3000";
  private static final String SET_GUEST =
      "guest_cart=3f2c9d; Path=/api/cart; Max-Age=2592000; HttpOnly; Secure; SameSite=Lax";
  private static final String CLEAR_GUEST =
      "guest_cart=; Path=/api/cart; Max-Age=0; HttpOnly; Secure; SameSite=Lax";
  private static final StubServer CORE = new StubServer();
  private static final CartUpstream CART = new CartUpstream();
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
    limiter.reset();
    CORE.resetCounters();
    CART.reset();
  }

  @Test
  void guestCartCookieReachesTheUpstreamUnchanged() {
    client
        .get()
        .uri("/api/cart")
        .header("Cookie", "guest_cart=3f2c9d")
        .exchange()
        .expectStatus()
        .isOk();

    Map<String, List<String>> seen = CART.lastHeaders("GET /api/cart");
    assertThat(seen.get("cookie")).containsExactly("guest_cart=3f2c9d");
    assertThat(seen).doesNotContainKey("authorization");
  }

  @Test
  void upstreamSetCookieForSetAndClearReachesTheClientUnchanged() {
    CART.setCookie(SET_GUEST);
    EntityExchangeResult<byte[]> set =
        client.put().uri("/api/cart").header("Origin", WEB).exchange().expectBody().returnResult();
    assertThat(set.getResponseHeaders().get("Set-Cookie")).containsExactly(SET_GUEST);

    CART.setCookie(CLEAR_GUEST);
    EntityExchangeResult<byte[]> clear =
        client
            .delete()
            .uri("/api/cart")
            .header("Origin", WEB)
            .exchange()
            .expectBody()
            .returnResult();
    assertThat(clear.getResponseHeaders().get("Set-Cookie")).containsExactly(CLEAR_GUEST);
  }

  @Test
  void validAccessCookieArrivesUpstreamAsBearerAndOtherCookiesStay() {
    String token = Tokens.valid(KEY);

    client
        .get()
        .uri("/api/cart")
        .header("Cookie", "access_token=" + token + "; logged_in=1; guest_cart=3f2c9d")
        .exchange()
        .expectStatus()
        .isOk();

    Map<String, List<String>> seen = CART.lastHeaders("GET /api/cart");
    assertThat(seen.get("authorization")).containsExactly("Bearer " + token);
  }

  @Test
  void invalidAccessCookieIsStrippedWhileLoggedInAndGuestCartPassThrough() {
    String expired =
        Tokens.sign(KEY, "key-1", Tokens.ISSUER, Tokens.AUDIENCE, Instant.now().minusSeconds(30));
    String badSignature =
        Tokens.sign(
            Tokens.newKey("key-1"),
            "key-1",
            Tokens.ISSUER,
            Tokens.AUDIENCE,
            Instant.now().plusSeconds(600));

    for (String token : List.of(expired, badSignature, "not-a-jwt")) {
      CART.reset();
      client
          .get()
          .uri("/api/cart")
          .header("Cookie", "access_token=" + token + "; logged_in=1; guest_cart=3f2c9d")
          .exchange()
          .expectStatus()
          .isOk();

      Map<String, List<String>> seen = CART.lastHeaders("GET /api/cart");
      assertThat(seen).doesNotContainKey("authorization");
      assertThat(seen.get("cookie")).containsExactly("logged_in=1; guest_cart=3f2c9d");
    }
  }

  @Test
  void writesWithAForeignOriginAreForbiddenAndNeverReachTheUpstream() {
    for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE)) {
      EntityExchangeResult<byte[]> result =
          client
              .method(method)
              .uri("/api/cart/items")
              .header("Origin", "https://evil.example")
              .exchange()
              .expectBody()
              .returnResult();

      assertThat(result.getStatus().value()).as(method.name()).isEqualTo(403);
      assertThat(result.getResponseHeaders().getContentType().toString())
          .startsWith("application/problem+json");
      assertThat(ProblemSchema.violations(new String(result.getResponseBody()))).isEmpty();
    }
    assertThat(CART.requestCount()).isZero();
  }

  @Test
  void writesWithTheWebOriginReachTheUpstream() {
    for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE)) {
      client
          .method(method)
          .uri("/api/cart/items")
          .header("Origin", WEB)
          .exchange()
          .expectStatus()
          .isOk();
      assertThat(CART.lastHeaders(method + " /api/cart/items")).isNotEmpty();
    }
  }

  @Test
  void cartRoutesAreNotRateLimited() {
    for (int i = 0; i < 100; i++) {
      client.get().uri("/api/cart").exchange().expectStatus().isOk();
    }

    assertThat(CART.requestCount()).isEqualTo(100);
  }

  /** Records the headers of each request per "METHOD path" and always answers 200. */
  private static final class CartUpstream implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, Map<String, List<String>>> seen = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String setCookie;

    CartUpstream() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      server.createContext("/", this::handle);
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void setCookie(String value) {
      this.setCookie = value;
    }

    void reset() {
      seen.clear();
      requests.set(0);
      setCookie = null;
    }

    int requestCount() {
      return requests.get();
    }

    Map<String, List<String>> lastHeaders(String methodAndPath) {
      return seen.getOrDefault(methodAndPath, Map.of());
    }

    private void handle(HttpExchange exchange) throws IOException {
      requests.incrementAndGet();
      seen.put(
          exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(),
          exchange.getRequestHeaders().entrySet().stream()
              .collect(
                  Collectors.toMap(e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue)));
      byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      if (setCookie != null) {
        exchange.getResponseHeaders().add("Set-Cookie", setCookie);
      }
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
