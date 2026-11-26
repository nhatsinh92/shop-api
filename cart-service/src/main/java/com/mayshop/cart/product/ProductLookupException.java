package com.mayshop.cart.product;

import java.util.Locale;

/**
 * {@code core} could not answer within the budget (timeout, connection error, non-2xx or an open
 * breaker) and some SKUs had no cached entry at all. Callers map it to 503 {@code
 * SERVICE_UNAVAILABLE} with {@code Retry-After: 2}.
 */
public class ProductLookupException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final int RETRY_AFTER_SECONDS = 2;

  /** Why the lookup failed; the lower-case name is the {@code reason} tag of the metric. */
  public enum Reason {
    TIMEOUT,
    ERROR,
    CIRCUIT_OPEN;

    public String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final Reason reason;

  public ProductLookupException(String message, Throwable cause) {
    this(Reason.ERROR, message, cause);
  }

  public ProductLookupException(String message) {
    this(Reason.ERROR, message, null);
  }

  public ProductLookupException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
