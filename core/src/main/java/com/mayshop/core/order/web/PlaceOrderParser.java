package com.mayshop.core.order.web;

import com.mayshop.core.checkout.Payments;
import com.mayshop.core.order.idempotency.RequestFingerprint;
import com.mayshop.core.order.internal.PlaceOrder;
import com.mayshop.core.order.web.InvalidOrderRequestException.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validates a {@code PlaceOrderRequest} by hand, so every violation names its field. */
final class PlaceOrderParser {

  static final Pattern PHONE = Pattern.compile("^(0|\\+84)[35789][0-9]{8}$");
  private static final Pattern KEY =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Set<String> SHIPPING = Set.of("STANDARD", "EXPRESS_2H");
  private static final Set<String> MOCK_OUTCOMES = Set.of("fail", "timeout");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PlaceOrderParser() {}

  /** The key as a lower-case UUID, or {@link IdempotencyKeyMissingException}. */
  static String key(String header) {
    if (header == null || !KEY.matcher(header.trim()).matches()) {
      throw new IdempotencyKeyMissingException();
    }
    return header.trim().toLowerCase(java.util.Locale.ROOT);
  }

  static PlaceOrder parse(UUID userId, String key, String body, String mockOutcome) {
    List<Violation> violations = new ArrayList<>();
    if (mockOutcome != null && !MOCK_OUTCOMES.contains(mockOutcome)) {
      violations.add(
          new Violation(
              "X-Mock-Payment-Outcome", "must be one of " + MOCK_OUTCOMES.size() + " values"));
    }
    JsonNode json;
    try {
      json = body == null || body.isBlank() ? null : JSON.readTree(body);
    } catch (RuntimeException e) {
      throw invalid("body", "must be valid JSON");
    }
    if (json == null || !json.isObject()) {
      throw invalid("body", "must be a JSON object");
    }
    UUID reservationId = null;
    JsonNode reservation = json.get("reservationId");
    if (reservation == null
        || !reservation.isString()
        || !KEY.matcher(reservation.asString()).matches()) {
      violations.add(new Violation("reservationId", "is required and must be a UUID"));
    } else {
      reservationId = UUID.fromString(reservation.asString());
    }
    String shippingOption = enumValue(json, "shippingOptionId", SHIPPING, violations);
    String method = enumValue(json, "paymentMethod", Set.of("COD", "EWALLET", "CARD"), violations);
    ObjectNode address = address(json.get("shippingAddress"), violations);
    String note = null;
    JsonNode noteNode = json.get("note");
    if (noteNode != null && !noteNode.isNull()) {
      if (!noteNode.isString() || noteNode.asString().length() > 500) {
        violations.add(new Violation("note", "must be a string of at most 500 characters"));
      } else {
        note = noteNode.asString();
      }
    }
    if (!violations.isEmpty()) {
      throw new InvalidOrderRequestException(violations);
    }
    return new PlaceOrder(
        userId,
        key,
        RequestFingerprint.of(json, mockOutcome),
        reservationId,
        shippingOption,
        Payments.Method.valueOf(method),
        JSON.writeValueAsString(address),
        note,
        mockOutcome);
  }

  private static String enumValue(
      JsonNode json, String field, Set<String> allowed, List<Violation> violations) {
    JsonNode node = json.get(field);
    if (node == null || !node.isString() || !allowed.contains(node.asString())) {
      violations.add(new Violation(field, "is required and must be a supported value"));
      return null;
    }
    return node.asString();
  }

  /** The address with only the contract's fields, or null after recording the violations. */
  private static ObjectNode address(JsonNode node, List<Violation> violations) {
    if (node == null || !node.isObject()) {
      violations.add(new Violation("shippingAddress", "is required and must be an object"));
      return null;
    }
    ObjectNode clean = JSON.createObjectNode();
    text(node, "fullName", 100, true, violations, clean);
    JsonNode phone = node.get("phone");
    if (phone == null || !phone.isString() || !PHONE.matcher(phone.asString()).matches()) {
      violations.add(new Violation("shippingAddress.phone", "must be a valid phone number"));
    } else {
      clean.put("phone", phone.asString());
    }
    text(node, "line1", 200, true, violations, clean);
    text(node, "ward", 100, true, violations, clean);
    text(node, "district", 100, false, violations, clean);
    text(node, "province", 100, true, violations, clean);
    return clean;
  }

  private static void text(
      JsonNode node,
      String field,
      int max,
      boolean required,
      List<Violation> violations,
      ObjectNode out) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      if (required) {
        violations.add(new Violation("shippingAddress." + field, "is required"));
      }
      return;
    }
    if (!value.isString() || value.asString().isEmpty() || value.asString().length() > max) {
      violations.add(
          new Violation(
              "shippingAddress." + field, "must be a string of 1 to " + max + " characters"));
      return;
    }
    out.put(field, value.asString());
  }

  private static InvalidOrderRequestException invalid(String field, String message) {
    return new InvalidOrderRequestException(List.of(new Violation(field, message)));
  }
}
