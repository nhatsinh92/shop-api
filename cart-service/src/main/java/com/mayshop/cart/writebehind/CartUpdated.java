package com.mayshop.cart.writebehind;

import com.mayshop.cart.repository.CartLine;
import com.mayshop.cart.repository.CartSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * The {@code cart.updated} record value: the full cart as one script call saw it. Internal to
 * {@code cart-service}; it is not part of the contract.
 */
public record CartUpdated(String cartKey, long version, Instant updatedAt, List<Item> items) {

  public CartUpdated {
    items = List.copyOf(items);
  }

  /** One line of the cart; {@code addedAt} is the time of the first add of the SKU. */
  public record Item(String sku, int quantity, Instant addedAt) {}

  public static CartUpdated of(String cartKey, CartSnapshot snapshot) {
    List<Item> items =
        snapshot.items().stream()
            .map((CartLine line) -> new Item(line.sku(), line.quantity(), line.addedAt()))
            .toList();
    return new CartUpdated(cartKey, snapshot.version(), snapshot.updatedAt(), items);
  }

  /** The snapshot of a cart that no longer exists (the guest key after a merge). */
  public static CartUpdated empty(String cartKey, Instant now) {
    return new CartUpdated(cartKey, 0, now, List.of());
  }
}
