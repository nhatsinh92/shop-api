package com.mayshop.core.auth.web;

/** The refresh token is missing, unknown, expired, revoked or reused. */
final class RefreshInvalidException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  RefreshInvalidException() {
    super("Refresh token invalid", null, false, false);
  }
}
