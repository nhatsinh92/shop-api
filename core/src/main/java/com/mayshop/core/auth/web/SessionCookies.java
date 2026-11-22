package com.mayshop.core.auth.web;

import java.time.Duration;
import java.util.List;
import org.springframework.http.ResponseCookie;

/** The three session cookies of the contract ({@code SetSessionCookies}). */
final class SessionCookies {

  static final String ACCESS = "access_token";
  static final String REFRESH = "refresh_token";
  static final String HINT = "logged_in";

  private SessionCookies() {}

  /** {@code Set-Cookie} header values for a new session. */
  static List<String> issue(
      String accessToken, Duration accessTtl, String refreshToken, Duration refreshTtl) {
    return List.of(
        cookie(ACCESS, accessToken, accessTtl, true).toString(),
        cookie(REFRESH, refreshToken, refreshTtl, true).toString(),
        cookie(HINT, "1", refreshTtl, false).toString());
  }

  private static ResponseCookie cookie(
      String name, String value, Duration maxAge, boolean httpOnly) {
    return ResponseCookie.from(name, value)
        .httpOnly(httpOnly)
        .secure(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build();
  }
}
