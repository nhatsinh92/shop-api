package com.mayshop.core.order.web;

/** The {@code Idempotency-Key} header is missing or not a UUID. */
class IdempotencyKeyMissingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  IdempotencyKeyMissingException() {
    super("Idempotency key missing");
  }
}
