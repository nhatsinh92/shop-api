package com.mayshop.core.order.web;

import java.util.List;

/** The request violates the contract; each violation names its field. */
class InvalidOrderRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<Violation> violations;

  InvalidOrderRequestException(List<Violation> violations) {
    super("Invalid order request");
    this.violations = List.copyOf(violations);
  }

  List<Violation> violations() {
    return violations;
  }

  record Violation(String field, String message) {}
}
