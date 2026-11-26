package com.mayshop.cart.product;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the SKUs of the running flash sale in the product cache. Once the application is ready, and
 * then every {@code cart.warmup.interval}, it reads {@code GET /api/flash-sales/current} from
 * {@code core} and looks the SKUs up through {@link ProductLookupService}, which fills the cache.
 * "No sale running" (404) is a successful no-op. Any failure is logged and counted as {@code
 * cart.warmup.failed}; it never stops the application or the schedule.
 *
 * <p>Runs on its own daemon thread, so a slow {@code core} never delays start-up or a request. Off
 * when {@code cart.warmup.enabled} is not {@code true} (the test default).
 */
@Component
@ConditionalOnProperty(name = "cart.warmup.enabled", havingValue = "true")
public class FlashSaleWarmup implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(FlashSaleWarmup.class);

  private final HttpClient http;
  private final JsonMapper json;
  private final URI uri;
  private final Duration timeout;
  private final Duration interval;
  private final ProductLookupService lookup;
  private final Counter failed;
  private final ScheduledExecutorService executor;

  @Autowired
  public FlashSaleWarmup(
      ProductLookupProperties properties,
      ProductLookupService lookup,
      JsonMapper json,
      MeterRegistry meters,
      @Value("${cart.warmup.interval:60s}") Duration interval,
      @Value("${cart.warmup.timeout:2s}") Duration timeout) {
    String base = properties.coreUrl();
    base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    this.uri = URI.create(base + "/api/flash-sales/current");
    this.lookup = lookup;
    this.json = json;
    this.interval = interval;
    this.timeout = timeout;
    this.failed = Counter.builder("cart.warmup.failed").register(meters);
    this.http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .build();
    this.executor =
        Executors.newSingleThreadScheduledExecutor(task -> {
          Thread thread = new Thread(task, "flash-sale-warmup");
          thread.setDaemon(true);
          return thread;
        });
  }

  /** Starts the schedule: the first run right away, then one {@code interval} after each end. */
  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    executor.scheduleWithFixedDelay(this::warmUp, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** One warm-up; never throws, so the schedule keeps running. */
  void warmUp() {
    try {
      List<String> skus = currentSaleSkus();
      if (skus.isEmpty()) {
        LOG.debug("Flash-sale warm-up: no running sale or no items");
        return;
      }
      lookup.lookup(new LinkedHashSet<>(skus));
      LOG.debug("Flash-sale warm-up cached {} SKUs", skus.size());
    } catch (Exception e) {
      failed.increment();
      LOG.warn("Flash-sale warm-up failed; it runs again in {}: {}", interval, e.toString());
    }
  }

  private List<String> currentSaleSkus() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/json")
            .GET()
            .build();
    HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() == 404) {
      return List.of();
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("core answered " + response.statusCode());
    }
    Sale sale = json.readValue(response.body(), Sale.class);
    Set<String> skus = new LinkedHashSet<>();
    if (sale != null && sale.items() != null) {
      for (Item item : sale.items()) {
        if (item != null && item.sku() != null && !item.sku().isBlank()) {
          skus.add(item.sku());
        }
      }
    }
    return List.copyOf(skus);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  private record Sale(List<Item> items) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  private record Item(String sku) {}
}
