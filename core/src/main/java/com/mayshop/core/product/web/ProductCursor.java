package com.mayshop.core.product.web;

import com.mayshop.core.product.persistence.CatalogQueries.PageKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The opaque list cursor: base64url JSON of the last item's {@code (created_at, id)}. */
final class ProductCursor {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ProductCursor() {}

  static String encode(PageKey key) {
    Map<String, String> json = new LinkedHashMap<>();
    json.put("createdAt", key.createdAt().toString());
    json.put("id", key.id().toString());
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(JSON.writeValueAsString(json).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Decodes a cursor; throws {@link InvalidCursorException} for anything this API did not issue.
   */
  static PageKey decode(String cursor) {
    try {
      JsonNode node =
          JSON.readTree(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
      return new PageKey(
          Instant.parse(node.get("createdAt").asString()),
          UUID.fromString(node.get("id").asString()));
    } catch (RuntimeException e) {
      throw new InvalidCursorException();
    }
  }
}
