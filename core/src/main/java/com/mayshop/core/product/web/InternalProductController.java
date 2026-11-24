package com.mayshop.core.product.web;

import com.mayshop.core.product.persistence.AvailabilityQueries;
import com.mayshop.core.product.update.ProductUpdates;
import com.mayshop.core.product.update.ProductUpdates.Request;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code updateInternalProduct} of {@code contracts/openapi/core.yaml}. The token is checked here,
 * before the body is read, so a caller without the token cannot tell this operation from a missing
 * route: every failure of the check is the same 404 as an unknown SKU.
 */
@RestController
class InternalProductController {

  static final String TOKEN_HEADER = InternalToken.HEADER;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ProductUpdates updates;
  private final AvailabilityQueries availability;
  private final Clock clock;
  private final int lowThreshold;
  private final InternalToken token;

  InternalProductController(
      ProductUpdates updates,
      AvailabilityQueries availability,
      ObjectProvider<Clock> clock,
      @Value("${product.low-stock-threshold}") int lowThreshold,
      InternalToken token) {
    this.updates = updates;
    this.availability = availability;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
    this.lowThreshold = lowThreshold;
    this.token = token;
  }

  @PatchMapping(
      path = "/internal/products/{sku}",
      consumes = {"application/json", "application/*+json", "*/*"})
  @Transactional
  ResponseEntity<AvailabilityController.Availability> updateInternalProduct(
      @PathVariable String sku,
      @RequestHeader(value = TOKEN_HEADER, required = false) String given,
      @RequestBody(required = false) String body) {
    if (!token.matches(given)) {
      throw new ProductNotFoundException();
    }
    updates.update(sku, parse(body));
    var row = availability.findBySku(sku).orElseThrow(ProductNotFoundException::new);
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(AvailabilityController.toAvailability(row, clock, lowThreshold));
  }

  /** Validates the body against {@code UpdateProductRequest}; any violation is a 400. */
  static Request parse(String body) {
    JsonNode json;
    try {
      json = body == null || body.isBlank() ? null : JSON.readTree(body);
    } catch (RuntimeException e) {
      throw new InvalidUpdateBodyException("body", "must be a JSON object");
    }
    if (json == null || !json.isObject() || json.isEmpty()) {
      throw new InvalidUpdateBodyException("body", "must have at least one property");
    }
    json.propertyNames()
        .forEach(name -> {
          if (!name.equals("price") && !name.equals("listPrice") && !name.equals("stock")) {
            throw new InvalidUpdateBodyException(name, "is not allowed");
          }
        });
    return new Request(money(json, "price"), money(json, "listPrice"), stock(json));
  }

  private static Long money(JsonNode json, String field) {
    JsonNode node = json.get(field);
    if (node == null) {
      return null;
    }
    JsonNode amount = node.isObject() ? node.get("amount") : null;
    JsonNode currency = node.isObject() ? node.get("currency") : null;
    if (amount == null
        || !amount.isIntegralNumber()
        || !amount.canConvertToLong()
        || amount.asLong() <= 0
        || currency == null
        || !currency.isString()
        || !"VND".equals(currency.asString())) {
      throw new InvalidUpdateBodyException(
          field, "must be {amount: positive integer, currency: VND}");
    }
    return amount.asLong();
  }

  private static Integer stock(JsonNode json) {
    JsonNode node = json.get("stock");
    if (node == null) {
      return null;
    }
    if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 0) {
      throw new InvalidUpdateBodyException("stock", "must be an integer of 0 or more");
    }
    return node.asInt();
  }
}
