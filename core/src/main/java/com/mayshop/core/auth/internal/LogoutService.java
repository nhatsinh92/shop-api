package com.mayshop.core.auth.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Revokes the session behind a logout request. */
@Service
public class LogoutService {

  private final RefreshTokenStore tokens;

  LogoutService(RefreshTokenStore tokens) {
    this.tokens = tokens;
  }

  /**
   * Revokes the session and all its refresh tokens.
   *
   * <p>The session comes from the refresh token (by hash, rotated and revoked tokens included) or,
   * when there is none, from {@code fallbackSessionId} (the {@code sid} of a valid access token).
   * An unknown token, or no credentials at all, is a no-op: logout never fails.
   */
  @Transactional
  public void logout(String rawRefreshToken, Optional<UUID> fallbackSessionId) {
    Optional<UUID> session = Optional.empty();
    if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
      session =
          tokens
              .findForUpdate(LoginService.sha256Hex(rawRefreshToken))
              .map(RefreshTokenStore.Found::sessionId);
    }
    if (session.isEmpty() && (rawRefreshToken == null || rawRefreshToken.isBlank())) {
      session = fallbackSessionId;
    }
    session.ifPresent(tokens::revokeSession);
  }
}
