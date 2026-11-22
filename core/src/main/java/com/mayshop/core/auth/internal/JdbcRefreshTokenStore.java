package com.mayshop.core.auth.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRefreshTokenStore implements RefreshTokenStore {

  private final JdbcClient jdbc;

  JdbcRefreshTokenStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Found> findForUpdate(String tokenHash) {
    return jdbc.sql(
            "SELECT r.id, r.session_id, s.user_id, u.member_tier, r.expires_at,"
                + " r.rotated_at IS NOT NULL AS rotated, r.revoked_at IS NOT NULL AS revoked,"
                + " s.revoked_at IS NOT NULL AS session_revoked"
                + " FROM auth.refresh_tokens r"
                + " JOIN auth.sessions s ON s.id = r.session_id"
                + " JOIN auth.users u ON u.id = s.user_id"
                + " WHERE r.token_hash = :hash FOR UPDATE OF r")
        .param("hash", tokenHash)
        .query((rs, i) ->
            new Found(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("member_tier"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getBoolean("rotated"),
                rs.getBoolean("revoked"),
                rs.getBoolean("session_revoked")))
        .optional();
  }

  @Override
  public void markRotated(UUID tokenId) {
    jdbc.sql("UPDATE auth.refresh_tokens SET rotated_at = now() WHERE id = :id")
        .param("id", tokenId)
        .update();
  }

  @Override
  public void insert(UUID id, UUID sessionId, String tokenHash, Instant expiresAt) {
    jdbc.sql(
            "INSERT INTO auth.refresh_tokens (id, session_id, token_hash, expires_at)"
                + " VALUES (:id, :sessionId, :hash, :expiresAt)")
        .param("id", id)
        .param("sessionId", sessionId)
        .param("hash", tokenHash)
        .param("expiresAt", Timestamp.from(expiresAt))
        .update();
  }

  @Override
  public void revokeSession(UUID sessionId) {
    jdbc.sql("UPDATE auth.sessions SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
        .param("id", sessionId)
        .update();
    jdbc.sql(
            "UPDATE auth.refresh_tokens SET revoked_at = now()"
                + " WHERE session_id = :id AND revoked_at IS NULL")
        .param("id", sessionId)
        .update();
  }
}
