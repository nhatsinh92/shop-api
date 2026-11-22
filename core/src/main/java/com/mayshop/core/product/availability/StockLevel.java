package com.mayshop.core.product.availability;

import java.util.OptionalInt;

/** Approximate stock level of a SKU, as the contract's {@code StockLevel}. */
public enum StockLevel {
  OUT_OF_STOCK,
  LOW,
  IN_STOCK;

  private static final int ROUNDING_STEP = 10;

  /** {@code OUT_OF_STOCK} at 0, {@code LOW} from 1 up to and including the threshold. */
  public static StockLevel of(int quantity, int lowThreshold) {
    if (quantity <= 0) {
      return OUT_OF_STOCK;
    }
    return quantity <= lowThreshold ? LOW : IN_STOCK;
  }

  /**
   * The approximate quantity shown for {@code LOW} stock: rounded down to a multiple of 10, at
   * least 1. Absent for every other level.
   */
  public OptionalInt approxQuantity(int quantity) {
    if (this != LOW) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(Math.max(1, quantity / ROUNDING_STEP * ROUNDING_STEP));
  }
}
