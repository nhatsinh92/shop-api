package com.mayshop.core.auth.web;

/** Body of {@code POST /api/auth/login}; the password is never logged or echoed. */
record LoginRequest(String email, String password) {

  private static final int MAX_EMAIL = 254;
  private static final int MAX_PASSWORD = 128;

  /** Mirrors {@code LoginRequest} in the contract. */
  boolean isValid() {
    return email != null
        && !email.isEmpty()
        && email.length() <= MAX_EMAIL
        && email.indexOf('@') > 0
        && email.indexOf('@') < email.length() - 1
        && email.indexOf('@') == email.lastIndexOf('@')
        && email.chars().noneMatch(Character::isWhitespace)
        && password != null
        && !password.isEmpty()
        && password.length() <= MAX_PASSWORD;
  }

  @Override
  public String toString() {
    return "LoginRequest[email=" + email + ", password=***]";
  }
}
