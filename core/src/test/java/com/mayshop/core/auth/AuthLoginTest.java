package com.mayshop.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mayshop.core.auth.AuthTestSupport.CountingEncoder;
import com.mayshop.core.auth.AuthTestSupport.InMemoryUsers;
import com.mayshop.core.auth.AuthTestSupport.RecordingSessions;
import com.mayshop.core.auth.internal.UserAccount;
import com.mayshop.core.contract.ContractValidator;
import java.security.MessageDigest;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = AuthTestSupport.DB_FREE_EXCLUDES)
@AutoConfigureMockMvc
@Import(AuthTestSupport.InMemoryAuthConfiguration.class)
class AuthLoginTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID USER_ID = UUID.fromString("0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0001");

  @DynamicPropertySource
  static void keys(DynamicPropertyRegistry registry) {
    registry.add("auth.jwt.private-key", () -> AuthTestSupport.privatePem(AuthTestSupport.KEYS));
  }

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryUsers users;
  @Autowired private RecordingSessions sessions;
  @Autowired private CountingEncoder encoder;

  @BeforeEach
  void seed() {
    users.clear();
    sessions.clear();
    users.add(
        new UserAccount(
            USER_ID, "Linh@Example.com", encoder.encode("correct-horse"), "Linh Nguyen", "GOLD"));
    encoder.matchCalls.set(0);
  }

  private MvcResult login(String json) throws Exception {
    return mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(json))
        .andReturn();
  }

  private static String body(String email, String password) {
    return JSON.writeValueAsString(java.util.Map.of("email", email, "password", password));
  }

  private static String cookieValue(List<String> cookies, String name) {
    return cookies.stream()
        .filter(c -> c.startsWith(name + "="))
        .map(c -> c.substring(name.length() + 1, c.indexOf(';')))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void loginMatchesTheEmailCaseInsensitively() throws Exception {
    MockHttpServletResponse response =
        login(body("LINH@example.COM", "correct-horse")).getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(JSON.readTree(response.getContentAsString()).get("id").asString())
        .isEqualTo(USER_ID.toString());
  }

  @Test
  void wrongPasswordReturns401WithInvalidCredentials() throws Exception {
    MockHttpServletResponse response = login(body("linh@example.com", "wrong")).getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(JSON.readTree(response.getContentAsString()).get("code").asString())
        .isEqualTo("AUTH_INVALID_CREDENTIALS");
    assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    assertThat(sessions.sessions).isEmpty();
  }

  @Test
  void unknownEmailReturnsTheSameBodyAsAWrongPassword() throws Exception {
    JsonNode wrongPassword =
        JSON.readTree(login(body("linh@example.com", "wrong")).getResponse().getContentAsString());
    MockHttpServletResponse unknown = login(body("nobody@example.com", "wrong")).getResponse();
    JsonNode unknownBody = JSON.readTree(unknown.getContentAsString());

    assertThat(unknown.getStatus()).isEqualTo(401);
    assertThat(unknownBody.get("code").asString()).isEqualTo("AUTH_INVALID_CREDENTIALS");
    assertThat(unknownBody.get("title")).isEqualTo(wrongPassword.get("title"));
    assertThat(unknownBody.get("status")).isEqualTo(wrongPassword.get("status"));
    assertThat(unknownBody.get("type")).isEqualTo(wrongPassword.get("type"));
    assertThat(unknownBody.has("detail")).isEqualTo(wrongPassword.has("detail"));
  }

  @Test
  void bcryptIsCalledOnceForAnUnknownEmailAndOnceForAWrongPassword() throws Exception {
    login(body("nobody@example.com", "whatever"));
    assertThat(encoder.matchCalls.get()).isEqualTo(1);

    encoder.matchCalls.set(0);
    login(body("linh@example.com", "wrong"));
    assertThat(encoder.matchCalls.get()).isEqualTo(1);
  }

  @Test
  void malformedBodiesReturn400ValidationFailed() throws Exception {
    List<String> bodies =
        List.of(
            "not json",
            "{}",
            "{\"email\":\"linh@example.com\"}",
            "{\"password\":\"x\"}",
            body("not-an-email", "x"),
            body("linh@example.com", ""),
            body("linh@example.com", "x".repeat(129)),
            body("a".repeat(250) + "@example.com", "x"));
    for (String json : bodies) {
      MockHttpServletResponse response = login(json).getResponse();

      assertThat(response.getStatus()).as(json).isEqualTo(400);
      assertThat(response.getContentType()).startsWith("application/problem+json");
      assertThat(JSON.readTree(response.getContentAsString()).get("code").asString())
          .isEqualTo("VALIDATION_FAILED");
    }
    assertThat(encoder.matchCalls.get()).isZero();
  }

  @Test
  void successfulLoginCreatesOneSessionAndOneHashedRefreshToken() throws Exception {
    MockHttpServletResponse response =
        login(body("linh@example.com", "correct-horse")).getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(sessions.sessions).hasSize(1);
    assertThat(sessions.sessions.get(0).userId()).isEqualTo(USER_ID);
    assertThat(sessions.refreshTokens).hasSize(1);
    var stored = sessions.refreshTokens.get(0);
    assertThat(stored.sessionId()).isEqualTo(sessions.sessions.get(0).id());
    String refresh = cookieValue(AuthTestSupport.setCookies(response), "refresh_token");
    // 256 random bits, base64url without padding: 43 characters.
    assertThat(refresh).matches("[A-Za-z0-9_-]{43}");
    String expectedHash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(refresh.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(stored.tokenHash()).isEqualTo(expectedHash).matches("[0-9a-f]{64}");
    assertThat(stored.tokenHash()).isNotEqualTo(refresh);
    assertThat(stored.expiresAt())
        .isBetween(
            java.time.Instant.now().plus(java.time.Duration.ofDays(14)).minusSeconds(60),
            java.time.Instant.now().plus(java.time.Duration.ofDays(14)).plusSeconds(60));
  }

  @Test
  void successfulLoginSetsThreeCookiesWithTheContractFlags() throws Exception {
    MockHttpServletResponse response =
        login(body("linh@example.com", "correct-horse")).getResponse();

    List<String> cookies = AuthTestSupport.setCookies(response);
    assertThat(cookies).hasSize(3);
    String access =
        cookies.stream().filter(c -> c.startsWith("access_token=")).findFirst().orElseThrow();
    String refresh =
        cookies.stream().filter(c -> c.startsWith("refresh_token=")).findFirst().orElseThrow();
    String hint =
        cookies.stream().filter(c -> c.startsWith("logged_in=")).findFirst().orElseThrow();
    assertThat(access)
        .contains("; Max-Age=600", "; Path=/", "; HttpOnly", "; Secure", "; SameSite=Lax");
    assertThat(refresh)
        .contains("; Max-Age=1209600", "; Path=/", "; HttpOnly", "; Secure", "; SameSite=Lax");
    assertThat(hint)
        .startsWith("logged_in=1;")
        .contains("; Max-Age=1209600", "; Path=/", "; Secure", "; SameSite=Lax")
        .doesNotContain("HttpOnly");
  }

  @Test
  void theBodyContainsNoToken() throws Exception {
    MockHttpServletResponse response =
        login(body("linh@example.com", "correct-horse")).getResponse();

    String responseBody = response.getContentAsString();
    List<String> cookies = AuthTestSupport.setCookies(response);
    assertThat(responseBody)
        .doesNotContain(cookieValue(cookies, "access_token"))
        .doesNotContain(cookieValue(cookies, "refresh_token"));
    assertThat(JSON.readTree(responseBody).propertyNames())
        .containsExactlyInAnyOrder("id", "email", "displayName", "memberTier");
  }

  @Test
  void loginCreatesNoHttpSession() throws Exception {
    MvcResult result = login(body("linh@example.com", "correct-horse"));

    assertThat(result.getRequest().getSession(false)).isNull();
    assertThat(result.getResponse().getHeaders("Set-Cookie"))
        .noneMatch(c -> c.startsWith("JSESSIONID"));
  }

  @Test
  void loginWithoutACsrfTokenIsAccepted() throws Exception {
    // CSRF is SameSite=Lax plus the gateway Origin check; no Spring CSRF token is required.
    assertThat(login(body("linh@example.com", "correct-horse")).getResponse().getStatus())
        .isEqualTo(200);
  }

  @Test
  void loginContractOk() throws Exception {
    MockHttpServletResponse response =
        login(body("linh@example.com", "correct-horse")).getResponse();

    // ContractValidator (an existing, protected test helper) validates only the first value of a
    // header, but the contract declares Set-Cookie as an array of three strings. That is the one
    // violation tolerated here; the three cookies are checked in full by the cookie tests above.
    assertThat(ContractValidator.core().validate(response, "login"))
        .containsOnly("header Set-Cookie: $: string found, array expected");
  }

  @Test
  void loginContractBadRequest() throws Exception {
    ContractValidator.core().assertValid(login("{}").getResponse(), "login");
  }

  @Test
  void loginContractUnauthorized() throws Exception {
    ContractValidator.core()
        .assertValid(login(body("linh@example.com", "wrong")).getResponse(), "login");
  }
}
