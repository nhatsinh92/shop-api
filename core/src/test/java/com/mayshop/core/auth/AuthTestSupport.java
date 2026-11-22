package com.mayshop.core.auth;

import com.mayshop.core.auth.internal.SessionStore;
import com.mayshop.core.auth.internal.UserAccount;
import com.mayshop.core.auth.internal.UserAccounts;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Test doubles and token helpers for the auth tests; no database or Docker needed. */
final class AuthTestSupport {

  static final String ISSUER = "mayshop-core";
  static final String AUDIENCE = "mayshop";
  static final String DB_FREE_EXCLUDES =
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

  /** One key pair for all tests, so Spring reuses a single application context. */
  static final KeyPair KEYS = newKeyPair();

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

  private AuthTestSupport() {}

  static KeyPair newKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String privatePem(KeyPair pair) {
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(pair.getPrivate().getEncoded());
    return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
  }

  /** Builds and signs a token; every claim can be set to a wrong value to test rejection. */
  static final class TokenBuilder {
    private String kid;
    private String alg = "RS256";
    private String issuer = ISSUER;
    private Object audience = AUDIENCE;
    private String subject;
    private Instant expiresAt = Instant.now().plusSeconds(600);
    private KeyPair signer;

    TokenBuilder(KeyPair signer, String kid, UUID subject) {
      this.signer = signer;
      this.kid = kid;
      this.subject = subject.toString();
    }

    TokenBuilder issuer(String value) {
      this.issuer = value;
      return this;
    }

    TokenBuilder audience(Object value) {
      this.audience = value;
      return this;
    }

    TokenBuilder expiresAt(Instant value) {
      this.expiresAt = value;
      return this;
    }

    TokenBuilder kid(String value) {
      this.kid = value;
      return this;
    }

    TokenBuilder signedBy(KeyPair value) {
      this.signer = value;
      return this;
    }

    TokenBuilder alg(String value) {
      this.alg = value;
      return this;
    }

    String build() {
      Map<String, Object> header = new LinkedHashMap<>();
      header.put("alg", alg);
      header.put("typ", "JWT");
      header.put("kid", kid);
      Map<String, Object> claims = new LinkedHashMap<>();
      claims.put("sub", subject);
      claims.put("sid", UUID.randomUUID().toString());
      claims.put("tier", "GOLD");
      claims.put("iat", Instant.now().minusSeconds(5).getEpochSecond());
      claims.put("exp", expiresAt.getEpochSecond());
      claims.put("iss", issuer);
      claims.put("aud", audience);
      String input = encode(header) + "." + encode(claims);
      if ("none".equals(alg)) {
        return input + ".";
      }
      try {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signer.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + B64URL.encodeToString(signature.sign());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    private static String encode(Map<String, Object> json) {
      return B64URL.encodeToString(JSON.writeValueAsString(json).getBytes(StandardCharsets.UTF_8));
    }
  }

  /** In-memory users, so token and login rules are tested without a database. */
  static final class InMemoryUsers implements UserAccounts {
    private final List<UserAccount> users = new CopyOnWriteArrayList<>();

    void add(UserAccount user) {
      users.add(user);
    }

    void clear() {
      users.clear();
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
      return users.stream().filter(u -> u.email().equalsIgnoreCase(email)).findFirst();
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
      return users.stream().filter(u -> u.id().equals(id)).findFirst();
    }
  }

  /** Records what login writes. */
  static final class RecordingSessions implements SessionStore {
    record Session(UUID id, UUID userId) {}

    record RefreshToken(UUID id, UUID sessionId, String tokenHash, Instant expiresAt) {}

    final List<Session> sessions = new CopyOnWriteArrayList<>();
    final List<RefreshToken> refreshTokens = new CopyOnWriteArrayList<>();

    void clear() {
      sessions.clear();
      refreshTokens.clear();
    }

    @Override
    public void createSession(UUID sessionId, UUID userId) {
      sessions.add(new Session(sessionId, userId));
    }

    @Override
    public void createRefreshToken(UUID id, UUID sessionId, String tokenHash, Instant expiresAt) {
      refreshTokens.add(new RefreshToken(id, sessionId, tokenHash, expiresAt));
    }
  }

  /** Real BCrypt (cost 12) that counts comparisons. */
  static final class CountingEncoder implements PasswordEncoder {
    private final PasswordEncoder delegate = new BCryptPasswordEncoder(12);
    final AtomicInteger matchCalls = new AtomicInteger();

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      matchCalls.incrementAndGet();
      return delegate.matches(rawPassword, encodedPassword);
    }
  }

  /** Replaces the JDBC-backed beans of the auth module. */
  @TestConfiguration(proxyBeanMethods = false)
  static class InMemoryAuthConfiguration {

    @Bean
    @Primary
    InMemoryUsers inMemoryUsers() {
      return new InMemoryUsers();
    }

    @Bean
    @Primary
    RecordingSessions recordingSessions() {
      return new RecordingSessions();
    }

    @Bean
    @Primary
    CountingEncoder countingEncoder() {
      return new CountingEncoder();
    }

    // The JDBC repositories are still created; they never run because the fakes are primary.
    @Bean
    JdbcClient jdbcClient() {
      return Mockito.mock(JdbcClient.class);
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      return Mockito.mock(PlatformTransactionManager.class);
    }
  }

  static List<String> setCookies(org.springframework.mock.web.MockHttpServletResponse response) {
    return new ArrayList<>(response.getHeaders("Set-Cookie"));
  }
}
