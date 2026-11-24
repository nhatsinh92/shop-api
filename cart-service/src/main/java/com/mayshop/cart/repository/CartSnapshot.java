package com.mayshop.cart.repository;

import java.time.Instant;
import java.util.List;

/**
 * A cart as one script call saw it: lines newest first, the write counter and the time of the last
 * write. A cart that does not exist has version 0, no lines and an epoch {@code updatedAt}.
 */
public record CartSnapshot(List<CartLine> items, long version, Instant updatedAt) {

  public CartSnapshot {
    items = List.copyOf(items);
  }
}
