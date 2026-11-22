package com.mayshop.core.auth.internal;

import com.mayshop.core.auth.jwt.AccessTokenIssuer;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rotates refresh tokens with a grace period and reuse detection. */
@Service
public class RefreshService {

  private static final Logger LOG = LoggerFactory.getLogger(RefreshService.class);

  private final RefreshTokenStore tokens;
  private final GraceStore grace;
  private final AccessTokenIssuer issuer;
  private final SessionProperties properties;
  private final Clock clock;

  RefreshService(
      RefreshTokenStore tokens,
      GraceStore grace,
      AccessTokenIssuer issuer,
      SessionProperties properties,
      Clock clock) {
    this.tokens = tokens;
    this.grace = grace;
    this.issuer = issuer;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Exchanges a refresh token for a new token pair.
   *
   * <p>Returns empty (never throws for a bad token) so that a reuse revocation is committed.
   */
  @Transactional
  public Optional<RefreshResult> refresh(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      return Optional.empty();
    }
    String hash = LoginService.sha256Hex(rawToken);
    Optional<RefreshTokenStore.Found> found = tokens.findForUpdate(hash);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    RefreshTokenStore.Found token = found.get();
    if (token.revoked()
        || token.sessionRevoked()
        || !token.expiresAt().isAfter(Instant.now(clock))) {
      return Optional.empty();
    }
    if (!token.rotated()) {
      tokens.markRotated(token.id());
      remember(hash, token.sessionId());
      return Optional.of(issue(token));
    }
    return switch (graceState(hash, token.sessionId())) {
      case WITHIN -> Optional.of(issue(token));
      case EXPIRED -> reused(token);
      case UNAVAILABLE -> Optional.empty();
    };
  }

  private void remember(String hash, UUID sessionId) {
    try {
      grace.remember(hash, sessionId, properties.refreshGrace());
    } catch (RuntimeException e) {
      // Rotation still succeeds; a parallel replay will be rejected instead of accepted.
      LOG.warn("Cannot write the refresh grace key; parallel refreshes may be rejected", e);
    }
  }

  private enum GraceState {
    WITHIN,
    EXPIRED,
    UNAVAILABLE
  }

  private GraceState graceState(String hash, UUID sessionId) {
    try {
      return grace.find(hash).filter(sessionId::equals).isPresent()
          ? GraceState.WITHIN
          : GraceState.EXPIRED;
    } catch (RuntimeException e) {
      // Without the Redis check the replay is rejected, but the session is not revoked: it may be
      // a legitimate parallel refresh and we cannot tell.
      LOG.warn("Cannot read the refresh grace key; rejecting the rotated token", e);
      return GraceState.UNAVAILABLE;
    }
  }

  private Optional<RefreshResult> reused(RefreshTokenStore.Found token) {
    LOG.warn(
        "Rotated refresh token reused after the grace period; revoking session {}",
        token.sessionId());
    tokens.revokeSession(token.sessionId());
    return Optional.empty();
  }

  private RefreshResult issue(RefreshTokenStore.Found token) {
    String refresh = LoginService.newRefreshToken();
    tokens.insert(
        UUID.randomUUID(),
        token.sessionId(),
        LoginService.sha256Hex(refresh),
        Instant.now(clock).plus(properties.refreshTokenTtl()));
    String access = issuer.issue(token.userId(), token.sessionId(), token.memberTier());
    return new RefreshResult(access, issuer.ttl(), refresh, properties.refreshTokenTtl());
  }
}
