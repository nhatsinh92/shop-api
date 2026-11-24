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
 */
@Service
public class ProductLookupService {

  static final String UNKNOWN_MARKER = "unknown";
  private static final String KEY_PREFIX = "product:";

  private final StringRedisTemplate redis;
  private final CoreProductClient core;
  private final JsonMapper json;
  private final ProductLookupProperties properties;
  private final Counter failed;
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
    this.failed = Counter.builder("cart.product_lookup.failed").register(meters);
    this.timer = Timer.builder("cart.product_lookup").register(meters);
  }

  public static String key(String sku) {
    return KEY_PREFIX + sku;
  }

  /**
   * @throws ProductLookupException when SKUs are missing from the cache and {@code core} fails
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
      } else {
        ProductInfo info = parse(value);
        if (info == null) {
          missing.add(sku);
        } else {
          known.put(sku, info);
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
      failed.increment();
      if (e instanceof ProductLookupException) {
        throw e;
      }
      throw new ProductLookupException("Product lookup failed: " + e, e);
    } finally {
      sample.stop(timer);
    }
    Set<String> asked = new HashSet<>(missing);
    for (ProductInfo item : items) {
      if (item.sku() != null && asked.remove(item.sku())) {
        known.put(item.sku(), item);
        redis
            .opsForValue()
            .set(key(item.sku()), json.writeValueAsString(item), properties.cacheTtl());
      }
    }
    for (String sku : asked) {
      unknown.add(sku);
      redis.opsForValue().set(key(sku), UNKNOWN_MARKER, properties.cacheTtl());
    }
  }

  private ProductInfo parse(String value) {
    try {
      return json.readValue(value, ProductInfo.class);
    } catch (JacksonException e) {
      return null;
    }
  }
}
