package com.mayshop.core.product.web;

/** No product has the requested slug. */
final class ProductNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  ProductNotFoundException() {
    super("Product not found", null, false, false);
  }
}
