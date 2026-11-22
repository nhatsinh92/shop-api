package com.mayshop.core.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.contract.ContractValidator;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class JwksEndpointTest {

  private static final List<String> PRIVATE_PARAMS = List.of("d", "p", "q", "dp", "dq", "qi");

  private static MockHttpServletResponse get(String... properties) throws Exception {
    MockHttpServletResponse[] holder = new MockHttpServletResponse[1];
    new ApplicationContextRunner()
        .withUserConfiguration(JwtConfiguration.class)
        .withPropertyValues(properties)
        .run(context -> {
          MockMvc mvc =
              MockMvcBuilders.standaloneSetup(
                      new JwksController(context.getBean(SigningKeys.class)))
                  .build();
          holder[0] =
              mvc.perform(
                      MockMvcRequestBuilders.get("/.well-known/jwks.json")
                          .accept(MediaType.APPLICATION_JSON))
                  .andReturn()
                  .getResponse();
        });
    return holder[0];
  }

  private static List<Map<String, String>> keys(MockHttpServletResponse response) throws Exception {
    Map<String, List<Map<String, String>>> body =
        JsonMapper.builder()
            .build()
            .readValue(response.getContentAsString(), new TypeReference<>() {});
    return body.get("keys");
  }

  @Test
  void jwksReturnsTheCurrentKeyWithCacheControl() throws Exception {
    KeyPair pair = TestKeys.pair(2048);

    MockHttpServletResponse response = get("auth.jwt.private-key=" + TestKeys.privatePem(pair));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=300");
    List<Map<String, String>> keys = keys(response);
    assertThat(keys).hasSize(1);
    assertThat(keys.get(0))
        .containsEntry("kty", "RSA")
        .containsEntry("use", "sig")
        .containsEntry("alg", "RS256")
        .containsKeys("kid", "n", "e")
        .containsEntry("e", "AQAB");
  }

  @Test
  void jwksIncludesThePreviousKeyWhenConfigured() throws Exception {
    KeyPair current = TestKeys.pair(2048);
    KeyPair previous = TestKeys.pair(2048);

    MockHttpServletResponse response =
        get(
            "auth.jwt.private-key=" + TestKeys.privatePem(current),
            "auth.jwt.previous-public-key=" + TestKeys.publicPem(previous));

    List<Map<String, String>> keys = keys(response);
    assertThat(keys).hasSize(2);
    assertThat(keys.get(0).get("kid")).isNotEqualTo(keys.get(1).get("kid"));
    assertThat(keys.get(1)).containsEntry("alg", "RS256").containsEntry("use", "sig");
  }

  @Test
  void jwksNeverContainsPrivateParameters() throws Exception {
    MockHttpServletResponse response =
        get(
            "auth.jwt.private-key=" + TestKeys.privatePem(TestKeys.pair(2048)),
            "auth.jwt.previous-public-key=" + TestKeys.publicPem(TestKeys.pair(2048)));

    for (Map<String, String> key : keys(response)) {
      assertThat(key.keySet()).doesNotContainAnyElementsOf(PRIVATE_PARAMS);
    }
  }

  @Test
  void jwksKidMatchesTheTokenHeaderKid() throws Exception {
    KeyPair pair = TestKeys.pair(2048);
    MockHttpServletResponse response = get("auth.jwt.private-key=" + TestKeys.privatePem(pair));

    assertThat(keys(response).get(0).get("kid"))
        .isEqualTo(SigningKeys.kidOf((java.security.interfaces.RSAPublicKey) pair.getPublic()));
  }

  @Test
  void jwksResponseMatchesTheContract() throws Exception {
    MockHttpServletResponse response =
        get("auth.jwt.private-key=" + TestKeys.privatePem(TestKeys.pair(2048)));

    ContractValidator.core().assertValid(response, "getJwks");
  }
}
