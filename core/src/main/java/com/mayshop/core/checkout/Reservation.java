package com.mayshop.core.checkout;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A reservation: stock held for one user until {@code expiresAt}. Public API of the module.
 *
 * @param userId the owner (the {@code sub} claim of the access token)
 */
public record Reservation(
    UUID id, UUID userId, Status status, Instant createdAt, Instant expiresAt, List<Item> items) {

  /** Lifecycle of a reservation, as the contract's {@code ReservationStatus}. */
  public enum Status {
    ACTIVE,
    EXPIRED,
    CONSUMED,
    RELEASED
  }

  /**
   * A reserved line.
   *
   * @param unitPrice the confirmed price, member discount included
   * @param basePrice the price before the member discount
   * @param priceChanged true only when the client sent an expected price that differs from {@code
   *     basePrice}
   * @param tookSaleStock whether the line took flash sale stock
   * @param variantLabel null for a single-variant product
   */
  public record Item(
      String sku,
      String name,
      String variantLabel,
      String imageUrl,
      int quantity,
      long unitPrice,
      long basePrice,
      boolean priceChanged,
      boolean tookSaleStock) {

    public long lineTotal() {
      return unitPrice * quantity;
    }
  }

  public long subtotal() {
    return items.stream().mapToLong(Item::lineTotal).sum();
  }
}
