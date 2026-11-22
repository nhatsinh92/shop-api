package com.mayshop.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mayshop.core.auth.internal.GraceStore;
import com.mayshop.core.auth.internal.RefreshTokenStore;
import com.mayshop.core.contract.ContractValidator;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = AuthTestSupport.DB_FREE_EXCLUDES)
@AutoConfigureMockMvc
@Import({AuthTestSupport.InMemoryAuthConfiguration.class, AuthRefreshTest.Fakes.class})
class AuthRefreshTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID USER_ID = UUID.fromString("0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0001");

  @DynamicPropertySource
  static void keys(DynamicPropertyRegistry registry) {
    registry.add("auth.jwt.private-key", () -> AuthTestSupport.privatePem(AuthTestSupport.KEYS));
  }

  /** Rows of auth.refresh_tokens and auth.sessions in memory. */
  static final class FakeTokens implements RefreshTokenStore {
    final class Row {
      final UUID id;
      final UUID sessionId;
      final Instant expiresAt;
      boolean rotated;
      boolean revoked;

      Row(UUID id, UUID sessionId, Instant expiresAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.expiresAt = expiresAt;
      }
    }

    final Map<String, Row> rows = new HashMap<>();
    final Set<UUID> revokedSessions = new HashSet<>();

    synchronized void clear() {
      rows.clear();
      revokedSessions.clear();
    }

    synchronized UUID seed(String rawToken, Instant expiresAt) {
      UUID session = UUID.randomUUID();
      insert(UUID.randomUUID(), session, sha256(rawToken), expiresAt);
      return session;
    }

    @Override
    public synchronized Optional<Found> findForUpdate(String hash) {
      return Optional.ofNullable(rows.get(hash))
          .map(r ->
              new Found(
                  r.id,
                  r.sessionId,
                  USER_ID,
                  "GOLD",
                  r.expiresAt,
                  r.rotated,
                  r.revoked,
                  revokedSessions.contains(r.sessionId)));
    }

    @Override
    public synchronized void markRotated(UUID tokenId) {
      rows.values().stream().filter(r -> r.id.equals(tokenId)).forEach(r -> r.rotated = true);
    }

    @Override
    public synchronized void insert(UUID id, UUID sessionId, String hash, Instant expiresAt) {
      rows.put(hash, new Row(id, sessionId, expiresAt));
    }

    @Override
    public synchronized void revokeSession(UUID sessionId) {
      revokedSessions.add(sessionId);
      rows.values().stream()
          .filter(r -> r.sessionId.equals(sessionId))
          .forEach(r -> r.revoked = true);
    }

    synchronized Row row(String rawToken) {
      return rows.get(sha256(rawToken));
    }

    synchronized long rowsOf(UUID sessionId) {
      return rows.values().stream().filter(r -> r.sessionId.equals(sessionId)).count();
    }
  }

  /** The Redis grace keys in memory; can simulate an outage. */
  static final class FakeGrace implements GraceStore {
    final Map<String, UUID> keys = new HashMap<>();
    final Map<String, Duration> ttls = new HashMap<>();
    volatile boolean down;

    synchronized void clear() {
      keys.clear();
      ttls.clear();
      down = false;
    }

    @Override
    public synchronized void remember(String oldHash, UUID sessionId, Duration ttl) {
      if (down) {
        throw new IllegalStateException("Redis is down");
      }
      keys.put(oldHash, sessionId);
      ttls.put(oldHash, ttl);
    }

    @Override
    public synchronized Optional<UUID> find(String oldHash) {
      if (down) {
        throw new IllegalStateException("Redis is down");
      }
      return Optional.ofNullable(keys.get(oldHash));
    }

    /** The grace window has passed: Redis expired every key. */
    synchronized void expireAll() {
      keys.clear();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fakes {
    @Bean
    @Primary
    FakeTokens fakeTokens() {
      return new FakeTokens();
    }

    @Bean
    @Primary
    FakeGrace fakeGrace() {
      return new FakeGrace();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private FakeTokens tokens;
  @Autowired private FakeGrace grace;

  @BeforeEach
  void reset() {
    tokens.clear();
    grace.clear();
  }

  private static String sha256(String raw) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String freshToken() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
  }

  private String seedToken() {
    String raw = freshToken();
    tokens.seed(raw, Instant.now().plus(Duration.ofDays(14)));
    return raw;
  }

  private MockHttpServletResponse refresh(String token) throws Exception {
    var request = post("/api/auth/refresh").accept(MediaType.APPLICATION_JSON);
    if (token != null) {
      request = request.cookie(new Cookie("refresh_token", token));
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private static String cookie(MockHttpServletResponse response, String name) {
    return response.getHeaders("Set-Cookie").stream()
        .filter(c -> c.startsWith(name + "="))
        .map(c -> c.substring(name.length() + 1, c.indexOf(';')))
        .findFirst()
        .orElseThrow();
  }

  private void assertRefreshInvalid(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(JSON.readTree(response.getContentAsString()).get("code").asString())
        .isEqualTo("AUTH_REFRESH_INVALID");
    assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    ContractValidator.core().assertValid(response, "refreshSession");
  }

  @Test
  void aValidTokenReturns204WithNewCookiesAndMarksTheOldTokenRotated() throws Exception {
    String old = seedToken();
    UUID session = tokens.row(old).sessionId;

    MockHttpServletResponse response = refresh(old);

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(response.getContentAsString()).isEmpty();
    List<String> cookies = List.copyOf(response.getHeaders("Set-Cookie"));
    assertThat(cookies).hasSize(3);
    assertThat(
            cookies.stream().filter(c -> c.startsWith("access_token=")).findFirst().orElseThrow())
        .contains("; Max-Age=600", "; Path=/", "; HttpOnly", "; Secure", "; SameSite=Lax");
    assertThat(
            cookies.stream().filter(c -> c.startsWith("refresh_token=")).findFirst().orElseThrow())
        .contains("; Max-Age=1209600", "; Path=/", "; HttpOnly", "; Secure", "; SameSite=Lax");
    assertThat(cookies.stream().filter(c -> c.startsWith("logged_in=")).findFirst().orElseThrow())
        .startsWith("logged_in=1;")
        .contains("; Max-Age=1209600", "; Path=/", "; Secure", "; SameSite=Lax")
        .doesNotContain("HttpOnly");
    String renewed = cookie(response, "refresh_token");
    assertThat(renewed).isNotEqualTo(old).matches("[A-Za-z0-9_-]{43}");
    assertThat(tokens.row(old).rotated).isTrue();
    assertThat(tokens.row(renewed).sessionId).isEqualTo(session);
    assertThat(tokens.row(renewed).rotated).isFalse();
    assertThat(response.getContentAsString()).doesNotContain(renewed);
  }

  @Test
  void rotationWritesTheGraceKeyWithTheConfiguredTtl() throws Exception {
    String old = seedToken();
    UUID session = tokens.row(old).sessionId;

    refresh(old);

    assertThat(grace.keys).containsEntry(sha256(old), session);
    assertThat(grace.ttls.get(sha256(old))).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void presentingTheOldTokenInsideTheWindowIssuesAFreshPairForTheSameSession() throws Exception {
    String old = seedToken();
    UUID session = tokens.row(old).sessionId;
    String first = cookie(refresh(old), "refresh_token");

    MockHttpServletResponse second = refresh(old);

    assertThat(second.getStatus()).isEqualTo(204);
    String sibling = cookie(second, "refresh_token");
    assertThat(sibling).isNotIn(old, first);
    assertThat(tokens.row(sibling).sessionId).isEqualTo(session);
    assertThat(tokens.revokedSessions).isEmpty();
  }

  @Test
  void twoParallelRefreshesWithTheSameTokenBothSucceedAndEveryTokenRefreshesOnce()
      throws Exception {
    assertSiblings(2);
  }

  @Test
  void fiveParallelRefreshesWithTheSameTokenBothSucceedAndEveryTokenRefreshesOnce()
      throws Exception {
    assertSiblings(5);
  }

  private void assertSiblings(int count) throws Exception {
    String old = seedToken();
    UUID session = tokens.row(old).sessionId;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(count);
    try {
      var start = new java.util.concurrent.CountDownLatch(1);
      List<java.util.concurrent.Future<MockHttpServletResponse>> futures =
          new java.util.ArrayList<>();
      for (int i = 0; i < count; i++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return refresh(old);
            }));
      }
      start.countDown();
      List<String> issued = new java.util.ArrayList<>();
      for (var future : futures) {
        MockHttpServletResponse response = future.get();
        assertThat(response.getStatus()).isEqualTo(204);
        issued.add(cookie(response, "refresh_token"));
      }
      assertThat(issued).doesNotHaveDuplicates();
      for (String token : issued) {
        assertThat(refresh(token).getStatus()).as("sibling refreshes once").isEqualTo(204);
      }
      assertThat(tokens.revokedSessions).doesNotContain(session);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aRotatedTokenAfterTheWindowRevokesTheSessionAndASiblingIsRejectedAfterwards()
      throws Exception {
    String old = seedToken();
    UUID session = tokens.row(old).sessionId;
    String successor = cookie(refresh(old), "refresh_token");
    String sibling = cookie(refresh(old), "refresh_token");
    grace.expireAll();

    assertRefreshInvalid(refresh(old));

    assertThat(tokens.revokedSessions).contains(session);
    assertRefreshInvalid(refresh(sibling));
    assertRefreshInvalid(refresh(successor));
  }

  @Test
  void aMissingCookieReturns401RefreshInvalid() throws Exception {
    assertRefreshInvalid(refresh(null));
  }

  @Test
  void anUnknownTokenReturns401RefreshInvalid() throws Exception {
    assertRefreshInvalid(refresh(freshToken()));
  }

  @Test
  void anExpiredTokenReturns401RefreshInvalid() throws Exception {
    String raw = freshToken();
    tokens.seed(raw, Instant.now().minusSeconds(1));

    assertRefreshInvalid(refresh(raw));
  }

  @Test
  void aTokenOfARevokedSessionReturns401RefreshInvalid() throws Exception {
    String raw = seedToken();
    tokens.revokeSession(tokens.row(raw).sessionId);
    tokens.rows.values().forEach(r -> r.revoked = false);

    assertRefreshInvalid(refresh(raw));
  }

  @Test
  void aRevokedTokenReturns401RefreshInvalid() throws Exception {
    String raw = seedToken();
    tokens.row(raw).revoked = true;

    assertRefreshInvalid(refresh(raw));
  }

  @Test
  void anInvalidAccessCookieDoesNotBlockRefresh() throws Exception {
    String raw = seedToken();

    MockHttpServletResponse response =
        mvc.perform(
                post("/api/auth/refresh")
                    .cookie(
                        new Cookie("refresh_token", raw), new Cookie("access_token", "garbage")))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(204);
  }

  @Test
  void withRedisDownAnUnrotatedTokenStillRefreshesAndAGraceReplayIsRejected() throws Exception {
    grace.down = true;
    String old = seedToken();
    UUID session = tokens.row(old).sessionId;

    MockHttpServletResponse first = refresh(old);

    assertThat(first.getStatus()).isEqualTo(204);
    assertRefreshInvalid(refresh(old));
    // Unknown whether the replay was legitimate, so the session is not revoked.
    assertThat(tokens.revokedSessions).doesNotContain(session);
    assertThat(refresh(cookie(first, "refresh_token")).getStatus()).isEqualTo(204);
  }

  @Test
  void withRedisDownARotatedTokenIsNeverAcceptedEvenIfTheKeyWasWrittenBefore() throws Exception {
    String old = seedToken();
    refresh(old);
    grace.down = true;

    assertRefreshInvalid(refresh(old));
  }

  @Test
  void refreshContractNoContent() throws Exception {
    MockHttpServletResponse response = refresh(seedToken());

    // Same tolerated ContractValidator limitation as loginContractOk: Set-Cookie is checked as
    // a single header value, but the contract declares an array of three cookies.
    assertThat(ContractValidator.core().validate(response, "refreshSession"))
        .containsOnly("header Set-Cookie: $: string found, array expected");
  }

  @Test
  void refreshContractUnauthorized() throws Exception {
    ContractValidator.core().assertValid(refresh(freshToken()), "refreshSession");
  }
}
