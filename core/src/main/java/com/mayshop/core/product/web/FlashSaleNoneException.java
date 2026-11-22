package com.mayshop.core.product.web;

/** No flash sale window contains the current instant. */
final class FlashSaleNoneException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  FlashSaleNoneException() {
    super("No flash sale is running", null, false, false);
  }
}
