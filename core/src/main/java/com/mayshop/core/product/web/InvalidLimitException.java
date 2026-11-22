package com.mayshop.core.product.web;

/** The {@code limit} query parameter is not an integer in the contract's range. */
final class InvalidLimitException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  InvalidLimitException() {
    super("Invalid limit", null, false, false);
  }
}
