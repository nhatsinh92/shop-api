package com.mayshop.core.auth.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.util.StringUtils;

/** Takes the access token from {@code Authorization: Bearer}, or else from the cookie. */
final class AccessTokenResolver implements BearerTokenResolver {

  static final String COOKIE = "access_token";
  private static final String PREFIX = "Bearer ";

  @Override
  public String resolve(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
      String token = header.substring(PREFIX.length()).strip();
      return StringUtils.hasText(token) ? token : null;
    }
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if (COOKIE.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
          return cookie.getValue();
        }
      }
    }
    return null;
  }
}
