package com.mayshop.cart.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mayshop.cart.product.Money;
import java.time.Instant;
import java.util.List;

/** The JSON shapes of the cart read endpoints, as in the contract's {@code Cart} schema. */
final class CartView {

  private CartView() {}

  /** {@code Cart}. */
  record Cart(List<Item> items, int itemCount, Money subtotal, Instant updatedAt) {}

  /** {@code CartItem}; optional fields are omitted when absent. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Item(
      String sku,
      String slug,
      String name,
      String imageUrl,
      String variantLabel,
      Money unitPrice,
      Money listPrice,
      String stockLevel,
      int quantity,
      Money lineTotal,
      boolean available) {}

  /** {@code CartSummary}. */
  record Summary(int itemCount) {}
}
