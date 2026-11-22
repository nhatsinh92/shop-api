package com.mayshop.core.auth.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Refresh-token reads and writes needed by rotation. */
public interface RefreshTokenStore {

  /** A stored refresh token with the state of its session and owner. */
  record Found(
      UUID id,
      UUID sessionId,
      UUID userId,
      String memberTier,
      Instant expiresAt,
      boolean rotated,
      boolean revoked,
      boolean sessionRevoked) {}

  /** Finds a token by hash and locks its row until the transaction ends. */
  Optional<Found> findForUpdate(String tokenHash);

  void markRotated(UUID tokenId);

  void insert(UUID id, UUID sessionId, String tokenHash, Instant expiresAt);

  /** Revokes the session and all its refresh tokens. */
  void revokeSession(UUID sessionId);
}
