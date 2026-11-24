package com.mayshop.cart.product;

/**
 * {@code core} could not answer within the budget (timeout, connection error or non-2xx) and some
 * SKUs were not cached. Callers map it to 503 {@code SERVICE_UNAVAILABLE} with {@code Retry-After:
 * 2}.
 */
public class ProductLookupException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final int RETRY_AFTER_SECONDS = 2;

  public ProductLookupException(String message, Throwable cause) {
    super(message, cause);
  }

  public ProductLookupException(String message) {
    super(message);
  }
}
