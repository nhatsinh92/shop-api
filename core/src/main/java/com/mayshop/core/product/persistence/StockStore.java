package com.mayshop.core.product.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Write side of stock holds: regular stock and flash sale stock, one SKU per call. */
public interface StockStore {

  /**
   * {@code UPDATE product.stock SET quantity = quantity - q WHERE sku = ? AND quantity >= q}.
   *
   * @return whether the quantity was taken; false when the stock is short or the SKU unknown
   */
  boolean take(String sku, int quantity);

  /** Units in stock; 0 when the SKU has no stock row. */
  int available(String sku);

  /** Adds the quantity back to the stock row, creating it when missing. */
  void give(String sku, int quantity);

  /** The cheapest sale item of the SKU whose sale window contains {@code now}. */
  Optional<SaleItem> currentSaleItem(String sku, Instant now);

  /**
   * Increments {@code sold} by {@code quantity} when {@code sold + quantity <= sale_stock}.
   *
   * @return whether the sale stock was taken
   */
  boolean takeSaleStock(SaleItem item, int quantity);

  /**
   * Decrements {@code sold} by {@code quantity} on the SKU's latest sale item that has sold at
   * least that much (never below zero).
   */
  void giveSaleStock(String sku, int quantity);

  /** A SKU's item in a flash sale. */
  record SaleItem(UUID saleId, String sku, long salePrice) {}
}
