package com.mayshop.cart.writebehind.store;

import com.mayshop.cart.writebehind.CartUpdated;
import java.time.Instant;
import java.util.List;

/**
 * One {@code cart_snapshot} row. {@code items} is {@code null} when the stored JSON is unreadable.
 */
public record StoredCart(
    String cartKey, long version, Instant updatedAt, List<CartUpdated.Item> items) {}
