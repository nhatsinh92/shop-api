package com.mayshop.cart.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drops the cached product of every {@code product.updated} event. It runs on the Kafka listener
 * thread, never on a request path; a malformed record is logged and skipped.
 *
 * <p>An event whose {@code changes} are only {@code STOCK} deletes {@code product:{sku}} and keeps
 * {@code product-stale:{sku}}: {@code core} publishes one for every stock hold and release, and
 * deleting the stale entry too would leave the hottest SKUs without a fallback exactly when {@code
 * core} is slow (#197). Any other event (a price or detail change, or no readable {@code changes})
 * deletes both, so a changed price is never served from the stale entry.
 */
@Component
public class ProductUpdatedConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(ProductUpdatedConsumer.class);

  private final StringRedisTemplate redis;
  private final JsonMapper json;

  public ProductUpdatedConsumer(StringRedisTemplate redis, JsonMapper json) {
    this.redis = redis;
    this.json = json;
  }

  @KafkaListener(topics = "product.updated", groupId = "cart-service")
  public void onProductUpdated(String value) {
    JsonNode event = parse(value);
    String sku = skuOf(event);
    if (sku == null) {
      LOG.warn("Skipping a malformed product.updated record");
      return;
    }
    redis.delete(ProductLookupService.key(sku));
    if (!stockOnly(event)) {
      redis.delete(ProductLookupService.staleKey(sku));
    }
  }

  private JsonNode parse(String value) {
    if (value == null) {
      return null;
    }
    try {
      return json.readTree(value);
    } catch (JacksonException e) {
      return null;
    }
  }

  private static String skuOf(JsonNode event) {
    JsonNode sku = event == null ? null : event.get("sku");
    return sku != null && sku.isString() && !sku.asString().isBlank() ? sku.asString() : null;
  }

  /** True when {@code changes} is a non-empty array holding only {@code "STOCK"}. */
  private static boolean stockOnly(JsonNode event) {
    JsonNode changes = event.get("changes");
    if (changes == null || !changes.isArray() || changes.isEmpty()) {
      return false;
    }
    for (JsonNode change : changes) {
      if (!change.isString() || !"STOCK".equals(change.asString())) {
        return false;
      }
    }
    return true;
  }
}
