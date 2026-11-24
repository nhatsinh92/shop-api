package com.mayshop.cart.repository;

/**
 * Cart storage. Every write is one atomic script call that returns the resulting snapshot, so two
 * concurrent writes never lose an update.
 */
public interface CartRepository {

  int MAX_QUANTITY = 10;
  int MAX_SKUS = 50;

  CartSnapshot read(CartKey key);

  /**
   * Adds {@code quantity} (1 to 10) units; {@code QTY_LIMIT} above 10 units, {@code ITEMS_LIMIT}
   * for a 51st SKU.
   */
  WriteResult add(CartKey key, String sku, int quantity);

  /** Sets the quantity (1 to 10) of a SKU already in the cart. */
  WriteResult set(CartKey key, String sku, int quantity);

  WriteResult remove(CartKey key, String sku);

  /** Moves the guest cart into the user cart and deletes the guest key. */
  MergeResult merge(CartKey user, CartKey guest);
}
