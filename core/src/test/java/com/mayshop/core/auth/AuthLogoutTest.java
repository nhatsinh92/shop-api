package com.mayshop.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mayshop.core.auth.AuthRefreshTest.FakeGrace;
import com.mayshop.core.auth.AuthRefreshTest.FakeTokens;
import com.mayshop.core.auth.AuthTestSupport.InMemoryUsers;
import com.mayshop.core.auth.internal.UserAccount;
import com.mayshop.core.auth.jwt.AccessTokenIssuer;
import com.mayshop.core.contract.ContractValidator;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = AuthTestSupport.DB_FREE_EXCLUDES)
@AutoConfigureMockMvc
@Import({AuthTestSupport.InMemoryAuthConfiguration.class, AuthRefreshTest.Fakes.class})
class AuthLogoutTest {

  private static final UUID USER_ID = UUID.fromString("0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0001");

  @DynamicPropertySource
  static void keys(DynamicPropertyRegistry registry) {
    registry.add("auth.jwt.private-key", () -> AuthTestSupport.privatePem(AuthTestSupport.KEYS));
  }

  @Autowired private MockMvc mvc;
  @Autowired private FakeTokens tokens;
  @Autowired private FakeGrace grace;
  @Autowired private InMemoryUsers users;
  @Autowired private AccessTokenIssuer issuer;

  @BeforeEach
  void reset() {
    tokens.clear();
    grace.clear();
    users.clear();
    users.add(new UserAccount(USER_ID, "linh@example.com", "unused", "Linh Nguyen", "GOLD"));
  }

  private static String sha256(String raw) {
    try {
      return HexFormat.of()
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

  private MockHttpServletResponse call(Cookie... cookies) throws Exception {
    var request = post("/api/auth/logout").accept(MediaType.APPLICATION_JSON);
    if (cookies.length > 0) {
      request = request.cookie(cookies);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse refresh(String token) throws Exception {
    return mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", token)))
        .andReturn()
        .getResponse();
  }

  private static String cookie(MockHttpServletResponse response, String name) {
    return response.getHeaders("Set-Cookie").stream()
        .filter(c -> c.startsWith(name + "="))
        .map(c -> c.substring(name.length() + 1, c.indexOf(';')))
        .findFirst()
        .orElseThrow();
  }

  private void assertCleared(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(response.getContentAsString()).isEmpty();
    List<String> cookies = List.copyOf(response.getHeaders("Set-Cookie"));
    assertThat(cookies).hasSize(3);
    assertThat(
            cookies.stream().filter(c -> c.startsWith("access_token=")).findFirst().orElseThrow())
        .startsWith("access_token=;")
        .contains("; Max-Age=0", "; Path=/", "; HttpOnly", "; Secure", "; SameSite=Lax");
    assertThat(
            cookies.stream().filter(c -> c.startsWith("refresh_token=")).findFirst().orElseThrow())
        .startsWith("refresh_token=;")
        .contains("; Max-Age=0", "; Path=/", "; HttpOnly", "; Secure", "; SameSite=Lax");
    assertThat(cookies.stream().filter(c -> c.startsWith("logged_in=")).findFirst().orElseThrow())
        .startsWith("logged_in=;")
        .contains("; Max-Age=0", "; Path=/", "; Secure", "; SameSite=Lax")
        .doesNotContain("HttpOnly");
  }

  @Test
  void logoutWithAValidRefreshCookieRevokesTheSessionAndClearsTheCookies() throws Exception {
    String raw = seedToken();
    UUID session = tokens.row(raw).sessionId;

    MockHttpServletResponse response = call(new Cookie("refresh_token", raw));

    assertCleared(response);
    assertThat(tokens.revokedSessions).containsExactly(session);
    assertThat(tokens.row(raw).revoked).isTrue();
  }

  @Test
  void logoutWithARotatedRefreshTokenStillRevokesTheSession() throws Exception {
    String old = seedToken();
    UUID session = tokens.row(old).sessionId;
    String successor = cookie(refresh(old), "refresh_token");

    assertCleared(call(new Cookie("refresh_token", old)));

    assertThat(tokens.revokedSessions).containsExactly(session);
    assertThat(tokens.row(successor).revoked).isTrue();
  }

  @Test
  void logoutWithOnlyAValidAccessTokenRevokesTheSessionOfItsSidClaim() throws Exception {
    String raw = seedToken();
    UUID session = tokens.row(raw).sessionId;
    String access = issuer.issue(USER_ID, session, "GOLD");

    MockHttpServletResponse response = call(new Cookie("access_token", access));

    assertCleared(response);
    assertThat(tokens.revokedSessions).containsExactly(session);
    assertThat(tokens.row(raw).revoked).isTrue();
  }

  @Test
  void logoutWithAnUnknownRefreshTokenReturns204AndRevokesNothing() throws Exception {
    String raw = seedToken();

    assertCleared(call(new Cookie("refresh_token", freshToken())));

    assertThat(tokens.revokedSessions).isEmpty();
    assertThat(tokens.row(raw).revoked).isFalse();
  }

  @Test
  void logoutWithAnAlreadyRevokedTokenReturns204() throws Exception {
    String raw = seedToken();
    tokens.revokeSession(tokens.row(raw).sessionId);

    assertCleared(call(new Cookie("refresh_token", raw)));
    assertCleared(call(new Cookie("refresh_token", raw)));
  }

  @Test
  void logoutWithNoCookiesReturns204AndClearsTheCookies() throws Exception {
    seedToken();

    assertCleared(call());

    assertThat(tokens.revokedSessions).isEmpty();
  }

  @Test
  void logoutWithAnInvalidAccessTokenAndNoRefreshCookieReturns204AndRevokesNothing()
      throws Exception {
    seedToken();

    assertCleared(call(new Cookie("access_token", "garbage")));

    assertThat(tokens.revokedSessions).isEmpty();
  }

  @Test
  void logoutWithAnAccessTokenOfAnUnknownUserRevokesNothing() throws Exception {
    String raw = seedToken();
    String access = issuer.issue(UUID.randomUUID(), tokens.row(raw).sessionId, "GOLD");

    assertCleared(call(new Cookie("access_token", access)));

    assertThat(tokens.revokedSessions).isEmpty();
  }

  @Test
  void theRefreshCookieWinsOverTheAccessTokenOfAnotherSession() throws Exception {
    String a = seedToken();
    String b = seedToken();
    UUID sessionA = tokens.row(a).sessionId;
    UUID sessionB = tokens.row(b).sessionId;
    String accessB = issuer.issue(USER_ID, sessionB, "GOLD");

    assertCleared(call(new Cookie("refresh_token", a), new Cookie("access_token", accessB)));

    assertThat(tokens.revokedSessions).containsExactly(sessionA);
  }

  @Test
  void refreshingAfterLogoutReturns401RefreshInvalid() throws Exception {
    String raw = seedToken();
    call(new Cookie("refresh_token", raw));

    MockHttpServletResponse response = refresh(raw);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("AUTH_REFRESH_INVALID");
  }

  @Test
  void aSiblingIssuedInsideTheGraceWindowIsRejectedAfterLogout() throws Exception {
    String old = seedToken();
    refresh(old);
    String sibling = cookie(refresh(old), "refresh_token");

    call(new Cookie("refresh_token", old));

    MockHttpServletResponse response = refresh(sibling);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("AUTH_REFRESH_INVALID");
    // The rotated token inside the window is rejected too, because its session is revoked.
    assertThat(refresh(old).getStatus()).isEqualTo(401);
  }

  @Test
  void loggingOutSessionADoesNotAffectSessionBOfTheSameUser() throws Exception {
    String a = seedToken();
    String b = seedToken();

    call(new Cookie("refresh_token", a));

    assertThat(refresh(a).getStatus()).isEqualTo(401);
    assertThat(refresh(b).getStatus()).isEqualTo(204);
    assertThat(tokens.revokedSessions).doesNotContain(tokens.row(b).sessionId);
  }

  @Test
  void logoutIgnoresAStaleAuthorizationHeader() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer garbage"))
            .andReturn()
            .getResponse();

    assertCleared(response);
  }

  @Test
  void logoutContractNoContent() throws Exception {
    MockHttpServletResponse response = call(new Cookie("refresh_token", seedToken()));

    // Same tolerated ContractValidator limitation as refreshContractNoContent: Set-Cookie is
    // checked as a single header value, but the contract declares an array of three cookies.
    assertThat(ContractValidator.core().validate(response, "logout"))
        .containsOnly("header Set-Cookie: $: string found, array expected");
  }

  @Test
  void logoutContractNoContentWithoutCookies() throws Exception {
    assertThat(ContractValidator.core().validate(call(), "logout"))
        .containsOnly("header Set-Cookie: $: string found, array expected");
  }
}
