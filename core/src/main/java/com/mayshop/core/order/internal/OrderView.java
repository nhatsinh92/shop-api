package com.mayshop.core.order.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/** An order as the contract's {@code Order} schema; the stored and returned JSON shape. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderView(
    UUID id,
    String number,
    String status,
    List<Item> items,
    Money subtotal,
    Money shippingFee,
    Money total,
    Payment payment,
    String createdAt) {

  /** Whole VND. */
  public record Money(long amount, String currency) {
    public static Money vnd(long amount) {
      return new Money(amount, "VND");
    }
  }

  /** One order line. */
  public record Item(String sku, int quantity, Money unitPrice, Money lineTotal) {}

  /** Payment method and state. */
  public record Payment(String method, String status) {}

  /** A copy with {@code items} replaced. */
  public OrderView withItems(List<Item> newItems) {
    return new OrderView(
        id, number, status, newItems, subtotal, shippingFee, total, payment, createdAt);
  }
}
