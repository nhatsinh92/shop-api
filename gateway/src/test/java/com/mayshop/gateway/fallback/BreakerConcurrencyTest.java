package com.mayshop.gateway.fallback;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.InMemoryRateLimiter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Concurrency alone never fails a breaker call: the breaker routes run without a bulkhead, so more
 * concurrent calls than Resilience4j's default bulkhead limit (25) all reach the upstream (#195).
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class BreakerConcurrencyTest {

  private static final int CONCURRENT = 40;
  private static final HeldUpstream CART = new HeldUpstream(CONCURRENT);

  @TestConfiguration(proxyBeanMethods = false)
  static class Config {
    @Bean
    @Primary
    InMemoryRateLimiter inMemoryRateLimiter() {
      return new InMemoryRateLimiter();
    }
  }

  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) {
    registry.add("CART_SERVICE_URL", CART::url);
    registry.add("GATEWAY_CART_READ_TIMEOUT", () -> "10000");
  }

  @AfterAll
  static void stop() {
    CART.close();
  }

  @LocalServerPort private int port;
  @Autowired private CircuitBreakerRegistry breakers;

  @Test
  void fortyConcurrentCartReadsAllAnswer200AndTheBreakerRecordsNoFailure() {
    breakers.circuitBreaker("cart-read").reset();
    HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/cart"))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();

    List<CompletableFuture<HttpResponse<String>>> calls =
        IntStream.range(0, CONCURRENT)
            .mapToObj(i -> http.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
            .toList();
    List<Integer> statuses =
        calls.stream().map(CompletableFuture::join).map(HttpResponse::statusCode).toList();

    assertThat(statuses).hasSize(CONCURRENT).containsOnly(200);
    assertThat(CART.allArrived()).isTrue();
    assertThat(breakers.circuitBreaker("cart-read").getMetrics().getNumberOfFailedCalls()).isZero();
  }

  /** A stub upstream that holds every response until {@code expected} requests are in flight. */
  static final class HeldUpstream implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CountDownLatch arrived;

    HeldUpstream(int expected) {
      arrived = new CountDownLatch(expected);
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      server.setExecutor(executor);
      server.createContext("/", this::handle);
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    boolean allArrived() {
      return arrived.getCount() == 0;
    }

    private void handle(HttpExchange exchange) throws IOException {
      arrived.countDown();
      try {
        arrived.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      try {
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      } finally {
        exchange.close();
      }
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
