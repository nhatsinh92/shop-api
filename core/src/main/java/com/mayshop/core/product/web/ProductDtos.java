package com.mayshop.core.product.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/** Response shapes of {@code core.yaml}: ProductPage, ProductSummary and ProductDetail. */
final class ProductDtos {

  private ProductDtos() {}

  record Money(long amount, String currency) {
    static Money vnd(long amount) {
      return new Money(amount, "VND");
    }
  }

  record Page(List<Summary> items, String nextCursor) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Summary(
      String sku,
      String slug,
      String name,
      String imageUrl,
      Money price,
      Money listPrice,
      List<String> badges) {}

  record Image(String url, String alt) {}

  record CategoryRef(String slug, String name) {}

  record Category(String slug, String name, List<CategoryRef> path) {}

  record Option(String name, String value) {}

  record Variant(String sku, List<Option> options, Money price) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Detail(
      UUID id,
      String sku,
      String slug,
      String name,
      String brand,
      String imageUrl,
      Money price,
      Money listPrice,
      List<String> badges,
      String description,
      List<Image> images,
      Category category,
      List<Variant> variants) {}
}
