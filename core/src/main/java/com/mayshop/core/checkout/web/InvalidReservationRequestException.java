package com.mayshop.core.checkout.web;

import java.util.List;

/** The body of a reservation request violates the contract; each violation names its field. */
class InvalidReservationRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<Violation> violations;

  InvalidReservationRequestException(List<Violation> violations) {
    super("Invalid reservation request");
    this.violations = List.copyOf(violations);
  }

  List<Violation> violations() {
    return violations;
  }

  record Violation(String field, String message) {}
}
