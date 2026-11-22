package com.mayshop.core.product.web;

/** The {@code limit} query parameter of the recommendations is not an integer in 1..20. */
final class InvalidRecommendationLimitException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  InvalidRecommendationLimitException() {
    super("Invalid limit", null, false, false);
  }
}
