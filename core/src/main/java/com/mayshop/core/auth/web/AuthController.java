package com.mayshop.core.auth.web;

import com.mayshop.core.auth.internal.LoginResult;
import com.mayshop.core.auth.internal.LoginService;
import com.mayshop.core.auth.internal.LogoutService;
import com.mayshop.core.auth.internal.RefreshResult;
import com.mayshop.core.auth.internal.RefreshService;
import com.mayshop.core.auth.internal.UserAccounts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract operations {@code login}, {@code refreshSession}, {@code logout} and {@code
 * getCurrentUser}.
 */
@RestController
class AuthController {

  private final LoginService logins;
  private final RefreshService refreshes;
  private final LogoutService logouts;
  private final UserAccounts users;
  private final JwtDecoder accessTokens;

  AuthController(
      LoginService logins,
      RefreshService refreshes,
      LogoutService logouts,
      UserAccounts users,
      JwtDecoder accessTokens) {
    this.logins = logins;
    this.refreshes = refreshes;
    this.logouts = logouts;
    this.users = users;
    this.accessTokens = accessTokens;
  }

  @PostMapping(
      path = "/api/auth/login",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<UserProfile> login(@RequestBody LoginRequest request) {
    if (!request.isValid()) {
      throw new InvalidRequestException();
    }
    LoginResult result = logins.login(request.email(), request.password());
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    SessionCookies.issue(
            result.accessToken(),
            result.accessTokenTtl(),
            result.refreshToken(),
            result.refreshTokenTtl())
        .forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));
    return response.cacheControl(CacheControl.noStore()).body(UserProfile.of(result.user()));
  }

  @PostMapping(path = "/api/auth/refresh")
  ResponseEntity<Void> refresh(
      @CookieValue(name = SessionCookies.REFRESH, required = false) String refreshToken) {
    RefreshResult result =
        refreshes.refresh(refreshToken).orElseThrow(RefreshInvalidException::new);
    ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
    SessionCookies.issue(
            result.accessToken(),
            result.accessTokenTtl(),
            result.refreshToken(),
            result.refreshTokenTtl())
        .forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));
    return response.header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
  }

  @PostMapping(path = "/api/auth/logout")
  ResponseEntity<Void> logout(
      @CookieValue(name = SessionCookies.REFRESH, required = false) String refreshToken,
      @CookieValue(name = SessionCookies.ACCESS, required = false) String accessToken) {
    boolean hasRefresh = refreshToken != null && !refreshToken.isBlank();
    logouts.logout(refreshToken, hasRefresh ? Optional.empty() : sessionOf(accessToken));
    ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
    SessionCookies.clear().forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));
    return response.header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
  }

  /** The {@code sid} of a valid access token; empty for a missing, expired or invalid one. */
  private Optional<UUID> sessionOf(String accessToken) {
    if (accessToken == null || accessToken.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(accessTokens.decode(accessToken).getClaimAsString("sid"))
          .map(UUID::fromString);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  @GetMapping(path = "/api/auth/me", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<UserProfile> me(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    return users
        .findById(userId)
        .map(user ->
            ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(UserProfile.of(user)))
        .orElseThrow(UnauthenticatedException::new);
  }
}
