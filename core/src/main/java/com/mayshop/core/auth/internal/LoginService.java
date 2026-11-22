package com.mayshop.core.auth.internal;

import com.mayshop.core.auth.jwt.AccessTokenIssuer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Checks credentials and starts a session. */
@Service
public class LoginService {

  private static final int REFRESH_TOKEN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserAccounts users;
  private final SessionStore sessions;
  private final PasswordEncoder encoder;
  private final AccessTokenIssuer issuer;
  private final SessionProperties properties;
  private final Clock clock;
  // Compared against when the email is unknown, so both failures cost one BCrypt comparison.
  private final String decoyHash;

  LoginService(
      UserAccounts users,
      SessionStore sessions,
      PasswordEncoder encoder,
      AccessTokenIssuer issuer,
      SessionProperties properties,
      Clock clock) {
    this.users = users;
    this.sessions = sessions;
    this.encoder = encoder;
    this.issuer = issuer;
    this.properties = properties;
    this.clock = clock;
    this.decoyHash = encoder.encode("decoy-" + UUID.randomUUID());
  }

  /**
   * Logs a user in.
   *
   * @throws InvalidCredentialsException when the email is unknown or the password is wrong
   */
  @Transactional
  public LoginResult login(String email, String password) {
    Optional<UserAccount> found = users.findByEmail(email);
    boolean matches =
        encoder.matches(password, found.map(UserAccount::passwordHash).orElse(decoyHash));
    if (found.isEmpty() || !matches) {
      throw new InvalidCredentialsException();
    }
    UserAccount user = found.get();
    UUID sessionId = UUID.randomUUID();
    sessions.createSession(sessionId, user.id());
    String refreshToken = newRefreshToken();
    sessions.createRefreshToken(
        UUID.randomUUID(),
        sessionId,
        sha256Hex(refreshToken),
        Instant.now(clock).plus(properties.refreshTokenTtl()));
    String accessToken = issuer.issue(user.id(), sessionId, user.memberTier());
    return new LoginResult(
        user, accessToken, issuer.ttl(), refreshToken, properties.refreshTokenTtl());
  }

  static String newRefreshToken() {
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Lowercase hex SHA-256, the only form in which a refresh token is stored. */
  static String sha256Hex(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
