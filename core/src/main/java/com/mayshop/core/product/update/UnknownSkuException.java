package com.mayshop.core.product.update;

/** No variant has the SKU. */
public class UnknownSkuException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  UnknownSkuException() {
    super("Unknown SKU", null, false, false);
  }
}
