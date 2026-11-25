package com.mayshop.core.order.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fingerprint of a request: SHA-256 of the canonical JSON of the parsed body (object keys
 * sorted, no whitespace) plus the mock-outcome header value. Key order and whitespace in the body
 * do not change it.
 */
public final class RequestFingerprint {

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private RequestFingerprint() {}

  /**
   * Returns the hex SHA-256 of the canonical body and the header value.
   *
   * @param body the request body as a JSON document
   * @param mockOutcome the {@code X-Mock-Payment-Outcome} header value, or {@code null}
   */
  public static String of(String body, String mockOutcome) {
    return of(MAPPER.readTree(body), mockOutcome);
  }

  /** Same as {@link #of(String, String)} for an already parsed body. */
  public static String of(JsonNode body, String mockOutcome) {
    String canonical = MAPPER.writeValueAsString(sorted(body));
    // The length prefix keeps a body and a header value from shifting into each other.
    String header = mockOutcome == null ? "" : mockOutcome;
    return sha256(canonical.length() + ":" + canonical + "|" + header);
  }

  private static Object sorted(JsonNode node) {
    return MAPPER.convertValue(node, Object.class);
  }

  private static String sha256(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }
}
