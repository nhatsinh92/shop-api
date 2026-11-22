package com.mayshop.core.product.update;

/** The new values are well formed but not acceptable (list price not above the price). */
public class InvalidUpdateException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  InvalidUpdateException() {
    super("List price must be above the price", null, false, false);
  }
}
