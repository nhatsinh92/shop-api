package com.mayshop.core.product.web;

/** The request body of the internal product update does not match the contract (400). */
class InvalidUpdateBodyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  InvalidUpdateBodyException(String field, String message) {
    super(message, null, false, false);
    this.field = field;
  }

  String field() {
    return field;
  }
}
