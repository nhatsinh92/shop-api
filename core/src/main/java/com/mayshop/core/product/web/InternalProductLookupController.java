package com.mayshop.core.product.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mayshop.core.product.availability.StockLevel;
import com.mayshop.core.product.lookup.VariantLabel;
import com.mayshop.core.product.persistence.LookupQueries;
import com.mayshop.core.product.persistence.LookupQueries.LookupRow;
import com.mayshop.core.product.web.ProductDtos.Money;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code lookupInternalProducts} of {@code contracts/openapi/core.yaml}. Like the product update,
 * the token is checked before the body is read and every failure of the check is a 404.
 */
@RestController
class InternalProductLookupController {

  static final int MAX_SKUS = 50;

  /**
   * A SKU is 1–64 letters, digits, dots, underscores or hyphens, starting with a letter or digit.
   */
  private static final Pattern SKU = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final LookupQueries queries;
  private final InternalToken token;
  private final Clock clock;
  private final int lowThreshold;

  InternalProductLookupController(
      LookupQueries queries,
      InternalToken token,
      ObjectProvider<Clock> clock,
      @Value("${product.low-stock-threshold}") int lowThreshold) {
    this.queries = queries;
    this.token = token;
    this.clock = clock.getIfAvailable(Clock::systemUTC);
    this.lowThreshold = lowThreshold;
  }

  @PostMapping(
      path = "/internal/products/lookup",
      consumes = {"application/json", "application/*+json", "*/*"})
  @Transactional(readOnly = true)
  ResponseEntity<Lookup> lookupInternalProducts(
      @RequestHeader(value = InternalToken.HEADER, required = false) String given,
      @RequestBody(required = false) String body) {
    if (!token.matches(given)) {
      throw new ProductNotFoundException();
    }
    Set<String> skus = parse(body);
    Map<String, LookupRow> bySku = new HashMap<>();
    queries.findBySkus(skus).forEach(row -> bySku.put(row.sku(), row));
    List<Item> items = new ArrayList<>();
    for (String sku : skus) {
      LookupRow row = bySku.get(sku);
      if (row != null) {
        items.add(toItem(row));
      }
    }
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Lookup(items));
  }

  private Item toItem(LookupRow row) {
    var availability =
        AvailabilityController.toAvailability(row.availability(), clock, lowThreshold);
    long price = availability.price().amount();
    boolean discounted = row.listPrice() != null && row.listPrice() > price;
    StockLevel level = availability.stockLevel();
    String label = row.variantCount() > 1 ? VariantLabel.of(row.options()) : null;
    return new Item(
        row.sku(),
        row.slug(),
        row.name(),
        row.imageUrl(),
        label,
        availability.price(),
        discounted ? Money.vnd(row.listPrice()) : null,
        level);
  }

  /** Validates the body against {@code ProductLookupRequest}; any violation is a 400. */
  static Set<String> parse(String body) {
    JsonNode json;
    try {
      json = body == null || body.isBlank() ? null : JSON.readTree(body);
    } catch (RuntimeException e) {
      throw new InvalidUpdateBodyException("body", "must be a JSON object");
    }
    if (json == null || !json.isObject()) {
      throw new InvalidUpdateBodyException("body", "must be a JSON object");
    }
    JsonNode array = json.get("skus");
    if (array == null || !array.isArray()) {
      throw new InvalidUpdateBodyException("skus", "must be an array of SKUs");
    }
    if (array.isEmpty() || array.size() > MAX_SKUS) {
      throw new InvalidUpdateBodyException(
          "skus", "must have between 1 and " + MAX_SKUS + " items");
    }
    Set<String> skus = new LinkedHashSet<>();
    for (JsonNode node : array) {
      if (!node.isString() || !SKU.matcher(node.asString()).matches()) {
        throw new InvalidUpdateBodyException("skus", "must contain only well-formed SKUs");
      }
      if (!skus.add(node.asString())) {
        throw new InvalidUpdateBodyException("skus", "must not contain duplicates");
      }
    }
    return skus;
  }

  record Lookup(List<Item> items) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Item(
      String sku,
      String slug,
      String name,
      String imageUrl,
      String variantLabel,
      Money price,
      Money listPrice,
      StockLevel stockLevel) {}
}
