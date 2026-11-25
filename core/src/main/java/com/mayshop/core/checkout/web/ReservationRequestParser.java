package com.mayshop.core.checkout.web;

import com.mayshop.core.checkout.Reservations.Line;
import com.mayshop.core.checkout.web.InvalidReservationRequestException.Violation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates a {@code CreateReservationRequest} by hand, so every violation names its field. */
final class ReservationRequestParser {

  static final int MAX_ITEMS = 50;
  static final int MAX_QUANTITY = 99;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ReservationRequestParser() {}

  /** The lines in request order, or an {@link InvalidReservationRequestException}. */
  static List<Line> parse(String body) {
    JsonNode json;
    try {
      json = body == null || body.isBlank() ? null : JSON.readTree(body);
    } catch (RuntimeException e) {
      throw invalid("body", "must be valid JSON");
    }
    if (json == null || !json.isObject()) {
      throw invalid("body", "must be a JSON object");
    }
    JsonNode items = json.get("items");
    if (items == null || !items.isArray()) {
      throw invalid("items", "is required and must be an array");
    }
    if (items.isEmpty() || items.size() > MAX_ITEMS) {
      throw invalid("items", "must have between 1 and " + MAX_ITEMS + " items");
    }
    List<Violation> violations = new ArrayList<>();
    List<Line> lines = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      line(items.get(i), "items[" + i + "]", seen, violations).ifPresent(lines::add);
    }
    if (!violations.isEmpty()) {
      throw new InvalidReservationRequestException(violations);
    }
    return List.copyOf(lines);
  }

  private static java.util.Optional<Line> line(
      JsonNode item, String path, Set<String> seen, List<Violation> violations) {
    if (!item.isObject()) {
      violations.add(new Violation(path, "must be an object"));
      return java.util.Optional.empty();
    }
    int before = violations.size();
    String sku = null;
    JsonNode skuNode = item.get("sku");
    if (skuNode == null || !skuNode.isString() || skuNode.asString().isBlank()) {
      violations.add(new Violation(path + ".sku", "is required and must be a non-empty string"));
    } else {
      sku = skuNode.asString();
      if (!seen.add(sku)) {
        violations.add(new Violation(path + ".sku", "must not repeat a SKU of another item"));
      }
    }
    int quantity = 0;
    JsonNode quantityNode = item.get("quantity");
    if (quantityNode == null
        || !quantityNode.isIntegralNumber()
        || !quantityNode.canConvertToInt()
        || quantityNode.asInt() < 1
        || quantityNode.asInt() > MAX_QUANTITY) {
      violations.add(
          new Violation(path + ".quantity", "must be an integer between 1 and " + MAX_QUANTITY));
    } else {
      quantity = quantityNode.asInt();
    }
    Long expected = null;
    JsonNode priceNode = item.get("expectedUnitPrice");
    if (priceNode != null && !priceNode.isNull()) {
      JsonNode amount = priceNode.isObject() ? priceNode.get("amount") : null;
      JsonNode currency = priceNode.isObject() ? priceNode.get("currency") : null;
      if (amount == null
          || !amount.isIntegralNumber()
          || !amount.canConvertToLong()
          || amount.asLong() < 0
          || currency == null
          || !currency.isString()
          || !"VND".equals(currency.asString())) {
        violations.add(
            new Violation(
                path + ".expectedUnitPrice",
                "must be an amount of VND, a non-negative integer, with currency VND"));
      } else {
        expected = amount.asLong();
      }
    }
    if (violations.size() > before) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(new Line(sku, quantity, expected));
  }

  private static InvalidReservationRequestException invalid(String field, String message) {
    return new InvalidReservationRequestException(List.of(new Violation(field, message)));
  }
}
