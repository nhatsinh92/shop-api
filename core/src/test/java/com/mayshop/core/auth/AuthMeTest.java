package com.mayshop.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mayshop.core.auth.AuthTestSupport.InMemoryUsers;
import com.mayshop.core.auth.AuthTestSupport.TokenBuilder;
import com.mayshop.core.auth.internal.UserAccount;
import com.mayshop.core.auth.jwt.SigningKeys;
import com.mayshop.core.contract.ContractValidator;
import jakarta.servlet.http.Cookie;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    properties = {
      AuthTestSupport.DB_FREE_EXCLUDES,
      "logging.level.org.springframework.security=TRACE",
      "logging.level.com.mayshop=TRACE"
    })
@AutoConfigureMockMvc
@Import(AuthTestSupport.InMemoryAuthConfiguration.class)
class AuthMeTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID USER_ID = UUID.fromString("0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0001");
  private static final KeyPair KEYS = AuthTestSupport.KEYS;
  private static final String KID = SigningKeys.kidOf((RSAPublicKey) KEYS.getPublic());

  @DynamicPropertySource
  static void keys(DynamicPropertyRegistry registry) {
    registry.add("auth.jwt.private-key", () -> AuthTestSupport.privatePem(KEYS));
  }

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryUsers users;

  @BeforeEach
  void seed() {
    users.clear();
    users.add(new UserAccount(USER_ID, "linh@example.com", "unused", "Linh Nguyen", "GOLD"));
  }

  private TokenBuilder token() {
    return new TokenBuilder(KEYS, KID, USER_ID);
  }

  private MockHttpServletResponse me(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.accept(MediaType.APPLICATION_JSON)).andReturn().getResponse();
  }

  private MockHttpServletResponse meWithBearer(String token) throws Exception {
    return me(get("/api/auth/me").header("Authorization", "Bearer " + token));
  }

  private MockHttpServletResponse meWithCookie(String token) throws Exception {
    return me(get("/api/auth/me").cookie(new Cookie("access_token", token)));
  }

  private void assertUnauthenticated(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    var json = JSON.readTree(response.getContentAsString());
    assertThat(json.get("code").asString()).isEqualTo("AUTH_UNAUTHENTICATED");
    assertThat(json.get("traceId").asString()).isNotBlank();
    ContractValidator.core().assertValid(response, "getCurrentUser");
  }

  @Test
  void meAcceptsAValidBearerToken() throws Exception {
    MockHttpServletResponse response = meWithBearer(token().build());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(JSON.readTree(response.getContentAsString()).get("email").asString())
        .isEqualTo("linh@example.com");
    ContractValidator.core().assertValid(response, "getCurrentUser");
  }

  @Test
  void meAcceptsAValidAccessTokenCookie() throws Exception {
    MockHttpServletResponse response = meWithCookie(token().build());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    ContractValidator.core().assertValid(response, "getCurrentUser");
  }

  @Test
  void meWithoutATokenReturns401() throws Exception {
    assertUnauthenticated(me(get("/api/auth/me")));
  }

  @Test
  void meWithABadSignatureReturns401() throws Exception {
    assertUnauthenticated(meWithBearer(token().signedBy(AuthTestSupport.newKeyPair()).build()));
  }

  @Test
  void meWithAnExpiredTokenReturns401() throws Exception {
    assertUnauthenticated(meWithBearer(token().expiresAt(Instant.now().minusSeconds(120)).build()));
  }

  @Test
  void meWithAWrongIssuerReturns401() throws Exception {
    assertUnauthenticated(meWithBearer(token().issuer("someone-else").build()));
  }

  @Test
  void meWithAWrongAudienceReturns401() throws Exception {
    assertUnauthenticated(meWithBearer(token().audience("other-app").build()));
  }

  @Test
  void meWithAnUnknownKidReturns401() throws Exception {
    assertUnauthenticated(meWithBearer(token().kid("unknown-kid").build()));
  }

  @Test
  void meWithATokenOfADeletedUserReturns401() throws Exception {
    assertUnauthenticated(meWithBearer(new TokenBuilder(KEYS, KID, UUID.randomUUID()).build()));
  }

  @Test
  void meWithAnUnsignedTokenReturns401() throws Exception {
    assertUnauthenticated(meWithBearer(token().alg("none").build()));
  }

  @Test
  void meWithGarbageReturns401() throws Exception {
    assertUnauthenticated(meWithBearer("not.a.jwt"));
  }

  @Test
  void aBearerTokenWinsOverTheCookie() throws Exception {
    MockHttpServletResponse response =
        me(
            get("/api/auth/me")
                .header("Authorization", "Bearer " + token().build())
                .cookie(new Cookie("access_token", "garbage")));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void publicOperationsAreReachableWithoutAToken() throws Exception {
    assertThat(me(get("/actuator/health")).getStatus()).isEqualTo(200);
    assertThat(me(get("/.well-known/jwks.json")).getStatus()).isEqualTo(200);
    // Login is public (400 for the empty body, not 401). Refresh answers 401 AUTH_REFRESH_INVALID
    // without a refresh cookie (see AuthRefreshTest), not AUTH_UNAUTHENTICATED; logout has no
    // handler yet (a later task), so 404 proves security let it through.
    assertThat(
            me(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .getStatus())
        .isEqualTo(400);
    MockHttpServletResponse refresh = me(post("/api/auth/refresh"));
    assertThat(JSON.readTree(refresh.getContentAsString()).get("code").asString())
        .isEqualTo("AUTH_REFRESH_INVALID");
    assertThat(me(post("/api/auth/logout")).getStatus()).isNotIn(401, 403);
    for (String path :
        new String[] {
          "/api/products",
          "/api/products/some-slug",
          "/api/products/SKU-1/availability",
          "/api/flash-sales/current",
          "/api/recommendations"
        }) {
      assertThat(me(get(path)).getStatus()).as(path).isNotIn(401, 403);
    }
  }

  @Test
  void protectedOperationsNeedAToken() throws Exception {
    for (String path :
        new String[] {
          "/api/products/some-slug/member-price", "/api/orders", "/api/checkout/shipping-options"
        }) {
      assertThat(me(get(path)).getStatus()).as(path).isEqualTo(401);
    }
  }

  @Test
  void noHttpSessionIsCreated() throws Exception {
    var result =
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token().build()))
            .andReturn();

    assertThat(result.getRequest().getSession(false)).isNull();
    assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
  }

  @Test
  void tokensAreNeverLogged(CapturedOutput output) throws Exception {
    String valid = token().build();
    String expired = token().expiresAt(Instant.now().minusSeconds(120)).build();
    String forged = token().signedBy(AuthTestSupport.newKeyPair()).build();

    meWithBearer(valid);
    meWithCookie(valid);
    meWithBearer(expired);
    meWithCookie(forged);

    assertThat(output.getAll())
        .doesNotContain(valid)
        .doesNotContain(expired)
        .doesNotContain(forged);
  }
}
