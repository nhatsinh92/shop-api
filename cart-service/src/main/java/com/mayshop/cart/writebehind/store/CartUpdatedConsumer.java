package com.mayshop.cart.writebehind.store;

import com.mayshop.cart.writebehind.CartUpdated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the Postgres copy of each cart. It runs on the Kafka listener thread, never on a request
 * path. A malformed record is logged and skipped; a database failure propagates so the error
 * handler of {@link WriteBehindConfiguration} retries it with backoff.
 */
@Component
public class CartUpdatedConsumer {

  public static final String GROUP = "cart-writer";

  private static final Logger LOG = LoggerFactory.getLogger(CartUpdatedConsumer.class);

  private final CartSnapshotStore store;
  private final JsonMapper json;

  public CartUpdatedConsumer(CartSnapshotStore store, JsonMapper json) {
    this.store = store;
    this.json = json;
  }

  @KafkaListener(
      topics = "cart.updated",
      groupId = GROUP,
      containerFactory = WriteBehindConfiguration.LISTENER_FACTORY,
      properties = "auto.offset.reset=earliest")
  public void onCartUpdated(String value) {
    CartUpdated record = parse(value);
    if (record == null) {
      LOG.warn("Skipping a malformed cart.updated record");
      return;
    }
    store.apply(record);
  }

  private CartUpdated parse(String value) {
    if (value == null) {
      return null;
    }
    try {
      CartUpdated record = json.readValue(value, CartUpdated.class);
      boolean valid =
          record != null
              && record.cartKey() != null
              && !record.cartKey().isBlank()
              && record.updatedAt() != null;
      return valid ? record : null;
    } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
      return null;
    }
  }
}
