package com.mayshop.cart.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The {@code guest_cart} cookie: a 128-bit random id, base64url without padding (22 characters).
 * Reading never creates an id; only {@link #newId()} does, and the write endpoints call it.
 */
@Component
public class GuestCartCookies {

  /** Name of the cookie. */
  public static final String NAME = "guest_cart";

  private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{22}$");
  private static final Duration MAX_AGE = Duration.ofDays(30);

  private final SecureRandom random = new SecureRandom();

  /** True when the value is a well-formed guest id. */
  public static boolean isValid(String value) {
    return value != null && VALID.matcher(value).matches();
  }

  /** The raw cookie value of the request, if the cookie is present (valid or not). */
  static Optional<String> rawValue(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    for (Cookie cookie : cookies) {
      if (NAME.equals(cookie.getName())) {
        return Optional.ofNullable(cookie.getValue());
      }
    }
    return Optional.empty();
  }

  /** Creates a new guest id from 128 random bits. */
  public String newId() {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** {@code Set-Cookie} header value that stores the guest id for 30 days. */
  public String setCookie(String guestId) {
    return cookie(guestId, MAX_AGE).toString();
  }

  /** {@code Set-Cookie} header value that clears the cookie ({@code Max-Age=0}). */
  public String clearCookie() {
    return cookie("", Duration.ZERO).toString();
  }

  private static ResponseCookie cookie(String value, Duration maxAge) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build();
  }
}
