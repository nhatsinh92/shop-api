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
 * Drops {@code product:{sku}} and {@code product-stale:{sku}} for every {@code product.updated}
 * event, whatever it changed. It runs on the Kafka listener thread, never on a request path; a
 * malformed record is logged and skipped.
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
    String sku = skuOf(value);
    if (sku == null) {
      LOG.warn("Skipping a malformed product.updated record");
      return;
    }
    redis.delete(ProductLookupService.key(sku));
    redis.delete(ProductLookupService.staleKey(sku));
  }

  private String skuOf(String value) {
    if (value == null) {
      return null;
    }
    try {
      JsonNode sku = json.readTree(value).get("sku");
      return sku != null && sku.isString() && !sku.asString().isBlank() ? sku.asString() : null;
    } catch (JacksonException e) {
      return null;
    }
  }
}
