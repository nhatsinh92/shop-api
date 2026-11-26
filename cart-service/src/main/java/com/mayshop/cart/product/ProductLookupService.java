package com.mayshop.cart.product;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Product data for a set of SKUs from the shared Redis cache ({@code product:{sku}}, one {@code
 * MGET}), calling {@code core} once for the SKUs that are missing. SKUs that {@code core} does not
 * know are cached as unknown under the same key.
 *
 * <p>Every known product is also written to {@code product-stale:{sku}}, which outlives the fresh
 * entry. When the call to {@code core} fails, times out or finds the breaker open, the stale
 * entries answer instead; only SKUs without any entry make the lookup fail.
 */
@Service
public class ProductLookupService {

  static final String UNKNOWN_MARKER = "unknown";
  private static final String KEY_PREFIX = "product:";
  private static final String STALE_KEY_PREFIX = "product-stale:";

  private final StringRedisTemplate redis;
  private final CoreProductClient core;
  private final JsonMapper json;
  private final ProductLookupProperties properties;
  private final MeterRegistry meters;
  private final Counter hit;
  private final Counter stale;
  private final Counter miss;
  private final Timer timer;

  public ProductLookupService(
      StringRedisTemplate redis,
      CoreProductClient core,
      JsonMapper json,
      ProductLookupProperties properties,
      MeterRegistry meters) {
    this.redis = redis;
    this.core = core;
    this.json = json;
    this.properties = properties;
    this.meters = meters;
    this.hit = cacheCounter(meters, "hit");
    this.stale = cacheCounter(meters, "stale");
    this.miss = cacheCounter(meters, "miss");
    this.timer = Timer.builder("cart.product_lookup").register(meters);
  }

  private static Counter cacheCounter(MeterRegistry meters, String result) {
    return Counter.builder("cart.product_cache").tag("result", result).register(meters);
  }

  public static String key(String sku) {
    return KEY_PREFIX + sku;
  }

  public static String staleKey(String sku) {
    return STALE_KEY_PREFIX + sku;
  }

  /**
   * @throws ProductLookupException when SKUs have no entry, fresh or stale, and {@code core} fails
   */
  public ProductLookupResult lookup(Set<String> skus) {
    List<String> ordered = new ArrayList<>(new LinkedHashSet<>(skus));
    Map<String, ProductInfo> known = new HashMap<>();
    Set<String> unknown = new HashSet<>();
    if (ordered.isEmpty()) {
      return new ProductLookupResult(known, unknown);
    }
    List<String> values = redis.opsForValue().multiGet(ordered.stream().map(s -> key(s)).toList());
    List<String> missing = new ArrayList<>();
    for (int i = 0; i < ordered.size(); i++) {
      String sku = ordered.get(i);
      String value = values == null ? null : values.get(i);
      if (value == null) {
        missing.add(sku);
      } else if (UNKNOWN_MARKER.equals(value)) {
        unknown.add(sku);
        hit.increment();
      } else {
        ProductInfo info = parse(value);
        if (info == null) {
          missing.add(sku);
        } else {
          known.put(sku, info);
          hit.increment();
        }
      }
    }
    if (!missing.isEmpty()) {
      fetch(missing, known, unknown);
    }
    return new ProductLookupResult(known, unknown);
  }

  private void fetch(List<String> missing, Map<String, ProductInfo> known, Set<String> unknown) {
    List<ProductInfo> items;
    Timer.Sample sample = Timer.start();
    try {
      items = core.lookup(missing);
    } catch (RuntimeException e) {
      ProductLookupException failure =
          e instanceof ProductLookupException ple
              ? ple
              : new ProductLookupException("Product lookup failed: " + e, e);
      meters.counter("cart.product_lookup.failed", "reason", failure.reason().tag()).increment();
      serveStale(missing, known, failure);
      return;
    } finally {
      sample.stop(timer);
    }
    Set<String> asked = new HashSet<>(missing);
    for (ProductInfo item : items) {
      if (item.sku() != null && asked.remove(item.sku())) {
        known.put(item.sku(), item);
        String value = json.writeValueAsString(item);
        redis.opsForValue().set(key(item.sku()), value, properties.cacheTtl());
        redis.opsForValue().set(staleKey(item.sku()), value, properties.staleTtl());
        miss.increment();
      }
    }
    for (String sku : asked) {
      unknown.add(sku);
      redis.opsForValue().set(key(sku), UNKNOWN_MARKER, properties.cacheTtl());
      redis.delete(staleKey(sku));
      miss.increment();
    }
  }

  /** Answers the SKUs {@code core} could not from their stale entries, or rethrows the failure. */
  private void serveStale(
      List<String> missing, Map<String, ProductInfo> known, ProductLookupException failure) {
    List<String> values;
    try {
      values = redis.opsForValue().multiGet(missing.stream().map(s -> staleKey(s)).toList());
    } catch (RuntimeException e) {
      // Redis is the other dependency: with no stale data to read, the original failure stands.
      miss.increment(missing.size());
      throw failure;
    }
    Map<String, ProductInfo> served = new HashMap<>();
    for (int i = 0; i < missing.size(); i++) {
      String value = values == null ? null : values.get(i);
      ProductInfo info = value == null ? null : parse(value);
      if (info != null) {
        served.put(missing.get(i), info);
      }
    }
    if (served.size() < missing.size()) {
      miss.increment(missing.size());
      throw failure;
    }
    known.putAll(served);
    stale.increment(served.size());
  }

  private ProductInfo parse(String value) {
    try {
      return json.readValue(value, ProductInfo.class);
    } catch (JacksonException e) {
      return null;
    }
  }
}
