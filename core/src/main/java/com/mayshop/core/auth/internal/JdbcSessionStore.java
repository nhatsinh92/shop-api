package com.mayshop.core.auth.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSessionStore implements SessionStore {

  private final JdbcClient jdbc;

  JdbcSessionStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void createSession(UUID sessionId, UUID userId) {
    jdbc.sql("INSERT INTO auth.sessions (id, user_id) VALUES (:id, :userId)")
        .param("id", sessionId)
        .param("userId", userId)
        .update();
  }

  @Override
  public void createRefreshToken(UUID id, UUID sessionId, String tokenHash, Instant expiresAt) {
    jdbc.sql(
            "INSERT INTO auth.refresh_tokens (id, session_id, token_hash, expires_at)"
                + " VALUES (:id, :sessionId, :hash, :expiresAt)")
        .param("id", id)
        .param("sessionId", sessionId)
        .param("hash", tokenHash)
        .param("expiresAt", Timestamp.from(expiresAt))
        .update();
  }
}
