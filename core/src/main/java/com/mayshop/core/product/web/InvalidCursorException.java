package com.mayshop.core.product.web;

/** The {@code cursor} query parameter is not one this API issued. */
final class InvalidCursorException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  InvalidCursorException() {
    super("Invalid cursor", null, false, false);
  }
}
