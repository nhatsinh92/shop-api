package com.mayshop.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Proves that {@code /internal/**} and other non-API paths never reach core. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalPathsNotRoutedTest {

  private static final RecordingStub CORE = new RecordingStub();
  private static final RecordingStub CART = new RecordingStub();

  @org.springframework.boot.test.web.server.LocalServerPort private int port;

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
    CORE.requests.clear();
    CART.requests.clear();
  }

  @Test
  void patchAndGetOnInternalProductsAreNotFoundAndNeverReachCore() throws IOException {
    for (String method : List.of("PATCH", "GET")) {
      Raw response = send(method, "/internal/products/SKU-ANC-PRO", "X-Internal-Token: secret");

      assertThat(response.status()).as(method).isEqualTo(404);
      assertThat(response.head()).contains("application/problem+json");
      assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
    }
    assertThat(CORE.requests).isEmpty();
    assertThat(CART.requests).isEmpty();
  }

  @Test
  void pathTricksNeverReachAnInternalPathOnCore() throws IOException {
    List<String> paths =
        List.of(
            "/api/../internal/products/x",
            "/api/%2e%2e/internal/products/x",
            "/api/%2E%2E/internal/products/x",
            "/api/..%2finternal/products/x",
            "//internal/products/x",
            "/API/internal/products/x",
            "/internal/products/x",
            "/internal%2fproducts/x",
            "/%69nternal/products/x");
    for (String path : paths) {
      for (String method : List.of("PATCH", "GET")) {
        send(method, path, "X-Internal-Token: secret");
      }
    }

    assertThat(CORE.requests)
        .allSatisfy(request -> {
          assertThat(request.path()).startsWith("/api/");
          assertThat(request.path().toLowerCase(Locale.ROOT)).doesNotContain("/../");
          assertThat(request.path()).doesNotStartWith("/internal");
        });
    assertThat(CART.requests).isEmpty();
  }

  @Test
  void aClientInternalTokenIsRemovedBeforeForwarding() throws IOException {
    Raw response = send("GET", "/api/products", "X-Internal-Token: secret");

    assertThat(response.status()).isEqualTo(200);
    assertThat(CORE.requests).hasSize(1);
    assertThat(CORE.requests.get(0).headers()).doesNotContainKey("x-internal-token");
  }

  @Test
  void jwksStaysUnrouted() throws IOException {
    for (String path : List.of("/.well-known/jwks.json", "/api/../.well-known/jwks.json")) {
      Raw response = send("GET", path);

      assertThat(response.status()).as(path).isNotEqualTo(200);
    }
    assertThat(CORE.requests).noneMatch(r -> r.path().contains(".well-known"));
    assertThat(CART.requests).isEmpty();
  }

  private Raw send(String method, String rawPath, String... headers) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      StringBuilder request =
          new StringBuilder(method + " " + rawPath + " HTTP/1.1\r\nHost: 127.0.0.1\r\n");
      for (String header : headers) {
        request.append(header).append("\r\n");
      }
      request.append("Content-Length: 0\r\nConnection: close\r\n\r\n");
      socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      String text = new String(read(socket.getInputStream()), StandardCharsets.UTF_8);
      int split = text.indexOf("\r\n\r\n");
      String head = split < 0 ? text : text.substring(0, split);
      String body = split < 0 ? "" : text.substring(split + 4);
      String[] statusLine = head.split(" ", 3);
      int status = statusLine.length > 1 ? Integer.parseInt(statusLine[1]) : -1;
      return new Raw(status, head, body);
    }
  }

  private static byte[] read(InputStream in) throws IOException {
    return in.readAllBytes();
  }

  private record Raw(int status, String head, String body) {}

  private record Received(String path, Map<String, List<String>> headers) {}

  /** A stub upstream that records the raw path and headers of every request it receives. */
  private static final class RecordingStub {
    final List<Received> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    RecordingStub() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      server.createContext("/", this::handle);
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
      requests.add(
          new Received(
              exchange.getRequestURI().getRawPath(),
              exchange.getRequestHeaders().entrySet().stream()
                  .collect(
                      Collectors.toMap(
                          e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue))));
      byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    }

    void close() {
      server.stop(0);
    }
  }
}
