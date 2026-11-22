package com.mayshop.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class AuthSchemaTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  /** Documented in seed/README.md. */
  private static final Map<String, String> DEMO_PASSWORDS =
      Map.of(
          "linh@example.com", "linh-gold-2026",
          "minh@example.com", "minh-silver-2026",
          "an@example.com", "an-standard-2026");

  @Autowired private JdbcClient jdbc;
  @Autowired private Flyway flyway;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void cleanUsers() {
    jdbc.sql("delete from auth.users").update();
  }

  @Test
  void migrationsCreateUsersSessionsAndRefreshTokensInTheAuthSchema() {
    List<String> tables =
        jdbc.sql("select table_name from information_schema.tables where table_schema = 'auth'")
            .query(String.class)
            .list();

    assertThat(tables).contains("users", "sessions", "refresh_tokens");
  }

  @Test
  void runningTheMigrationsTwiceIsANoOp() {
    var result = flyway.migrate();

    assertThat(result.migrationsExecuted).isZero();
    assertThat(flyway.info().pending()).isEmpty();
  }

  @Test
  void emailIsUniqueIgnoringCase() {
    insertUser("linh@example.com", "STANDARD");

    assertThatThrownBy(() -> insertUser("Linh@Example.com", "STANDARD"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void memberTierOnlyAcceptsTheContractValues() {
    for (String tier : List.of("STANDARD", "SILVER", "GOLD", "PLATINUM")) {
      insertUser(tier.toLowerCase() + "@example.com", tier);
    }

    assertThatThrownBy(() -> insertUser("bronze@example.com", "BRONZE"))
        .hasMessageContaining("users_member_tier_check");
  }

  @Test
  void refreshTokenColumnsStoreOnlyTheSha256HashNeverARawToken() {
    UUID session = insertSession(insertUser("linh@example.com", "GOLD"));
    String hash = "a".repeat(64);
    insertRefreshToken(session, hash);

    // A raw 256-bit token (base64url, 43 characters) cannot be stored: only 64 hex characters can.
    assertThatThrownBy(() -> insertRefreshToken(session, "x".repeat(43)))
        .hasMessageContaining("refresh_tokens");
    assertThatThrownBy(() -> insertRefreshToken(session, hash))
        .isInstanceOf(DuplicateKeyException.class);

    List<String> columns =
        jdbc.sql(
                "select column_name from information_schema.columns"
                    + " where table_schema = 'auth' and table_name = 'refresh_tokens'")
            .query(String.class)
            .list();
    assertThat(columns).doesNotContain("token", "raw_token", "value");
  }

  @Test
  void refreshTokensAndSessionsAreRemovedWithTheirOwner() {
    UUID user = insertUser("linh@example.com", "GOLD");
    UUID session = insertSession(user);
    insertRefreshToken(session, "b".repeat(64));

    jdbc.sql("delete from auth.users where id = :id").param("id", user).update();

    assertThat(jdbc.sql("select count(*) from auth.refresh_tokens").query(Long.class).single())
        .isZero();
  }

  @Test
  void seededDemoPasswordsMatchTheirBcryptCost12Hashes() {
    var populator =
        new ResourceDatabasePopulator(
            new FileSystemResource(Path.of("..", "seed", "users.sql").toAbsolutePath()));
    populator.execute(dataSource);
    populator.execute(dataSource); // idempotent

    var encoder = new BCryptPasswordEncoder(12);
    assertThat(jdbc.sql("select count(*) from auth.users").query(Long.class).single())
        .isEqualTo(DEMO_PASSWORDS.size());
    DEMO_PASSWORDS.forEach((email, password) -> {
      String hash =
          jdbc.sql("select password_hash from auth.users where email = :email")
              .param("email", email)
              .query(String.class)
              .single();
      assertThat(encoder.matches(password, hash)).as(email).isTrue();
      assertThat(hash).startsWith("$2a$12$");
    });
    assertThat(jdbc.sql("select distinct member_tier from auth.users").query(String.class).list())
        .contains("GOLD", "STANDARD");
  }

  private UUID insertUser(String email, String tier) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into auth.users (id, email, password_hash, display_name, member_tier)"
                + " values (:id, :email, 'hash', 'Name', :tier)")
        .param("id", id)
        .param("email", email)
        .param("tier", tier)
        .update();
    return id;
  }

  private UUID insertSession(UUID user) {
    UUID id = UUID.randomUUID();
    jdbc.sql("insert into auth.sessions (id, user_id) values (:id, :user)")
        .param("id", id)
        .param("user", user)
        .update();
    return id;
  }

  private void insertRefreshToken(UUID session, String hash) {
    jdbc.sql(
            "insert into auth.refresh_tokens (id, session_id, token_hash, expires_at)"
                + " values (:id, :session, :hash, now() + interval '14 days')")
        .param("id", UUID.randomUUID())
        .param("session", session)
        .param("hash", hash)
        .update();
  }
}
