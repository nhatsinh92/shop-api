package com.mayshop.cart.security;

import com.mayshop.cart.web.Problems;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the {@link CallerIdentity} of every {@code /api/cart/**} request. Runs after the Bearer
 * filter, which has already answered 401 for an invalid or expired token.
 *
 * <ul>
 *   <li>verified Bearer token: the user (id is {@code sub});
 *   <li>no Bearer and the {@code logged_in=1} hint cookie: 401, because the access token expired
 *       and the client must refresh instead of writing into a guest cart;
 *   <li>otherwise a guest. An invalid {@code guest_cart} value is treated as absent and counted.
 * </ul>
 *
 * <p>Nothing here creates a guest id.
 */
public class CallerIdentityFilter extends OncePerRequestFilter {

  /** Request attribute that holds the {@link CallerIdentity}. */
  public static final String ATTRIBUTE = CallerIdentity.class.getName();

  private static final String HINT_COOKIE = "logged_in";

  private final MeterRegistry meters;

  /** Creates the filter; invalid guest cookies are counted in {@code meters}. */
  public CallerIdentityFilter(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(path.equals("/api/cart") || path.startsWith("/api/cart/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<String> guestId = guestId(request);
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    CallerIdentity identity;
    if (authentication instanceof JwtAuthenticationToken token) {
      identity = new CallerIdentity.User(token.getToken().getSubject(), guestId.orElse(null));
    } else if (hasHint(request)) {
      Problems.write(response, HttpStatus.UNAUTHORIZED, "Unauthenticated", "AUTH_UNAUTHENTICATED");
      return;
    } else {
      identity = new CallerIdentity.Guest(guestId.orElse(null));
    }
    request.setAttribute(ATTRIBUTE, identity);
    chain.doFilter(request, response);
  }

  private Optional<String> guestId(HttpServletRequest request) {
    Optional<String> raw = GuestCartCookies.rawValue(request);
    if (raw.isPresent() && !GuestCartCookies.isValid(raw.get())) {
      meters.counter("cart.guest_cookie.invalid").increment();
      return Optional.empty();
    }
    return raw;
  }

  private static boolean hasHint(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return false;
    }
    for (Cookie cookie : cookies) {
      if (HINT_COOKIE.equals(cookie.getName()) && "1".equals(cookie.getValue())) {
        return true;
      }
    }
    return false;
  }
}
