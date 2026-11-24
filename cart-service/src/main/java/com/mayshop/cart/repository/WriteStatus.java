package com.mayshop.cart.repository;

/** Outcome of a cart write. Anything but {@code OK} changed nothing. */
public enum WriteStatus {
  OK,
  QTY_LIMIT,
  ITEMS_LIMIT,
  ITEM_NOT_FOUND
}
