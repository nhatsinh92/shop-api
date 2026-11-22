package com.mayshop.core.auth.internal;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Short-lived memory of just-rotated refresh tokens ({@code auth:grace:{hash}}). */
public interface GraceStore {

  /** Remembers that the token with this hash was just rotated in the given session. */
  void remember(String oldHash, UUID sessionId, Duration ttl);

  /** The session of a token still inside its grace window. Throws if the store is unavailable. */
  Optional<UUID> find(String oldHash);
}
