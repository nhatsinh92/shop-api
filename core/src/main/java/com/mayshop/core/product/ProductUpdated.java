package com.mayshop.core.product;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * A product's price, stock or detail changed. Published as a Spring application event after the
 * change committed and externalised to the Redpanda topic {@code product.updated} (record key =
 * SKU, value = this record as JSON, shape {@code ProductUpdated} in {@code contracts/openapi/}).
 * Delivery is at least once, so consumers deduplicate by {@code eventId}.
 *
 * @param changes what changed, at least one, without duplicates
 * @param tags cache tags affected: {@code product-{id}} and {@code products} (plus {@code
 *     flash-sale}) for PRICE or DETAIL; only {@code product-{id}} for STOCK alone
 */
@Externalized(ProductUpdated.TOPIC + "::#{sku()}")
public record ProductUpdated(
    UUID eventId,
    Instant occurredAt,
    UUID productId,
    String sku,
    String slug,
    List<Change> changes,
    List<String> tags) {

  /** The Redpanda topic. */
  public static final String TOPIC = "product.updated";

  /** What changed on the product. */
  public enum Change {
    PRICE,
    STOCK,
    DETAIL
  }
}
