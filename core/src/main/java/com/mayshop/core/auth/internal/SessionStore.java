package com.mayshop.core.auth.internal;

import java.time.Instant;
import java.util.UUID;

/** Write access to {@code auth.sessions} and {@code auth.refresh_tokens}. */
public interface SessionStore {

  void createSession(UUID sessionId, UUID userId);

  /** Stores the SHA-256 hash (lowercase hex) of a refresh token; the raw token is never stored. */
  void createRefreshToken(UUID id, UUID sessionId, String tokenHash, Instant expiresAt);
}
