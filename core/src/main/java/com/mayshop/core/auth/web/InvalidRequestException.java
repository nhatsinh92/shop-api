package com.mayshop.core.auth.web;

/** The request body does not match the contract schema. */
final class InvalidRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  InvalidRequestException() {
    super("Invalid request", null, false, false);
  }
}
