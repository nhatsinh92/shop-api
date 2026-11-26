package com.mayshop.cart.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.cart.product.FakeRedis.MutableClock;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The flash-sale warm-up against a stub {@code core}, on an in-memory Redis. */
class FlashSaleWarmupTest {

  private HttpServer server;
  private final AtomicInteger saleCalls = new AtomicInteger();
  private final AtomicInteger lookupCalls = new AtomicInteger();
  private volatile int saleStatus = 200;
  private volatile String saleBody = sale("SKU-A", "SKU-B");
  private volatile long saleDelayMillis;

  private SimpleMeterRegistry meters;
  private ProductLookupService lookup;
  private ProductLookupProperties properties;
  private FlashSaleWarmup warmup;

  private static String sale(String... skus) {
    StringBuilder items = new StringBuilder();
    for (String sku : skus) {
      items.append(items.isEmpty() ? "" : ",").append("{\"sku\":\"").append(sku).append("\"}");
    }
    return "{\"id\":\"7c9e6679-7425-40de-944b-e07fc1f90ae7\",\"items\":[" + items + "]}";
  }

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/flash-sales/current",
        exchange -> {
          saleCalls.incrementAndGet();
          if (saleDelayMillis > 0) {
            try {
              Thread.sleep(saleDelayMillis);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          reply(exchange, saleStatus, saleBody);
        });
    server.createContext(
        "/internal/products/lookup",
        exchange -> {
          lookupCalls.incrementAndGet();
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          StringBuilder items = new StringBuilder();
          for (String sku : new String[] {"SKU-A", "SKU-B"}) {
            if (request.contains(sku)) {
              items.append(items.isEmpty() ? "" : ",").append(StubCore.item(sku, 100));
            }
          }
          reply(exchange, 200, "{\"items\":[" + items + "]}");
        });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    MutableClock clock = new MutableClock();
    FakeRedis redis = new FakeRedis(clock);
    meters = new SimpleMeterRegistry();
    properties =
        new ProductLookupProperties(url, "t", Duration.ofMillis(500), Duration.ofSeconds(60));
    JsonMapper json = JsonMapper.builder().build();
    lookup =
        new ProductLookupService(
            redis.template, new CoreProductClient(properties, json), json, properties, meters);
    warmup =
        new FlashSaleWarmup(
            properties, lookup, json, meters, Duration.ofMillis(50), Duration.ofMillis(300));
  }

  private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    try {
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    } catch (IOException e) {
      // the client gave up (timeout test)
    } finally {
      exchange.close();
    }
  }

  @AfterEach
  void stop() {
    warmup.close();
    server.stop(0);
  }

  private double failures() {
    return meters.counter("cart.warmup.failed").count();
  }

  @Test
  void afterWarmupACartReadOfASaleSkuMakesNoFurtherCallToCore() {
    warmup.warmUp();
    int callsAfterWarmup = lookupCalls.get();

    ProductLookupResult result = lookup.lookup(Set.of("SKU-A", "SKU-B"));

    assertThat(result.known()).containsKeys("SKU-A", "SKU-B");
    assertThat(callsAfterWarmup).isEqualTo(1);
    assertThat(lookupCalls.get()).isEqualTo(callsAfterWarmup);
    assertThat(failures()).isZero();
  }

  @Test
  void noRunningSaleIsASuccessfulNoOp() {
    saleStatus = 404;
    saleBody = "{\"title\":\"No flash sale is running\"}";

    warmup.warmUp();

    assertThat(saleCalls.get()).isEqualTo(1);
    assertThat(lookupCalls.get()).isZero();
    assertThat(failures()).isZero();
  }

  @Test
  void aSaleWithoutItemsIsASuccessfulNoOp() {
    saleBody = sale();

    warmup.warmUp();

    assertThat(lookupCalls.get()).isZero();
    assertThat(failures()).isZero();
  }

  @Test
  void aServerErrorIsCountedAndDoesNotThrow() {
    saleStatus = 503;

    warmup.warmUp();

    assertThat(failures()).isEqualTo(1);
    assertThat(lookupCalls.get()).isZero();
  }

  @Test
  void anUnparsableBodyIsCountedAndDoesNotThrow() {
    saleBody = "<html>not json</html>";

    warmup.warmUp();

    assertThat(failures()).isEqualTo(1);
  }

  @Test
  void aTimeoutIsCountedAndDoesNotThrow() {
    saleDelayMillis = 1_000;

    warmup.warmUp();

    assertThat(failures()).isEqualTo(1);
  }

  @Test
  void aConnectionRefusedIsCountedAndDoesNotThrow() throws Exception {
    server.stop(0);

    warmup.warmUp();

    assertThat(failures()).isEqualTo(1);
  }

  @Test
  void aFailedLookupOfTheSaleSkusIsCounted() {
    properties =
        new ProductLookupProperties(
            "http://127.0.0.1:1", "t", Duration.ofMillis(200), Duration.ofSeconds(60));
    JsonMapper json = JsonMapper.builder().build();
    ProductLookupService failing =
        new ProductLookupService(
            new FakeRedis(new MutableClock()).template,
            new CoreProductClient(properties, json),
            json,
            properties,
            meters);
    FlashSaleWarmup viaFailingLookup =
        new FlashSaleWarmup(
            new ProductLookupProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "t",
                Duration.ofMillis(200),
                Duration.ofSeconds(60)),
            failing,
            json,
            meters,
            Duration.ofMillis(50),
            Duration.ofMillis(300));

    viaFailingLookup.warmUp();
    viaFailingLookup.close();

    assertThat(failures()).isEqualTo(1);
  }

  @Test
  void theNextRunStillHappensAfterAFailure() throws Exception {
    saleStatus = 503;
    warmup.start();
    waitFor(() -> saleCalls.get() >= 1);

    saleStatus = 200;

    waitFor(() -> lookupCalls.get() >= 1);
    assertThat(saleCalls.get()).isGreaterThanOrEqualTo(2);
    assertThat(failures()).isGreaterThanOrEqualTo(1);
  }

  private static void waitFor(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met in time");
      }
      Thread.sleep(20);
    }
  }
}
