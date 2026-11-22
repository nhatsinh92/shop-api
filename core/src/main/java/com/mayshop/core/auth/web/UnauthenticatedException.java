package com.mayshop.core.auth.web;

/** The token is valid but its user no longer exists. */
final class UnauthenticatedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  UnauthenticatedException() {
    super("Unauthenticated", null, false, false);
  }
}
