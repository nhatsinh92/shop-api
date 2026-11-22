package com.mayshop.core.auth.web;

import com.mayshop.core.auth.internal.LoginResult;
import com.mayshop.core.auth.internal.LoginService;
import com.mayshop.core.auth.internal.UserAccounts;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Contract operations {@code login} and {@code getCurrentUser}. */
@RestController
class AuthController {

  private final LoginService logins;
  private final UserAccounts users;

  AuthController(LoginService logins, UserAccounts users) {
    this.logins = logins;
    this.users = users;
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
