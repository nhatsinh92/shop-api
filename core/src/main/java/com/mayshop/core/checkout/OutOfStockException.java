package com.mayshop.core.checkout;

import com.mayshop.core.product.StockHolds.ShortLine;
import java.util.List;

/**
 * At least one line of a reservation request lacks stock. It is a runtime exception, so the
 * transaction of {@link Reservations#create} rolls back and nothing changes.
 */
public class OutOfStockException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<ShortLine> unavailable;

  public OutOfStockException(List<ShortLine> unavailable) {
    super("Not enough stock for " + unavailable.size() + " line(s)");
    this.unavailable = List.copyOf(unavailable);
  }

  /** Every short line, with the quantity requested and the quantity available. */
  public List<ShortLine> unavailable() {
    return unavailable;
  }
}
