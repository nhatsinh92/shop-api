package com.mayshop.core.auth.internal;

/** Wrong password or unknown email; the two are deliberately not told apart. */
public final class InvalidCredentialsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidCredentialsException() {
    super("Invalid credentials", null, false, false);
  }
}
