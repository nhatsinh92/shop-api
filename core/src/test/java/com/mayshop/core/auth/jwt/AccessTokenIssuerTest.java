package com.mayshop.core.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class AccessTokenIssuerTest {

  private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static AccessTokenIssuer issuer(KeyPair pair, Duration ttl) {
    JwtProperties properties = new JwtProperties(null, null, "mayshop-core", "mayshop", ttl);
    SigningKeys keys =
        new SigningKeys(
            (java.security.interfaces.RSAPrivateKey) pair.getPrivate(),
            (RSAPublicKey) pair.getPublic(),
            null);
    return new AccessTokenIssuer(keys, properties, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static Map<String, Object> part(String token, int index) {
    byte[] json = Base64.getUrlDecoder().decode(token.split("\\.")[index]);
    return JSON.readValue(new String(json, StandardCharsets.UTF_8), new TypeReference<>() {});
  }

  @Test
  void accessTokenIsRs256WithKidHeaderAndAllClaims() throws Exception {
    KeyPair pair = TestKeys.pair(2048);
    AccessTokenIssuer issuer = issuer(pair, Duration.ofMinutes(10));
    UUID user = UUID.randomUUID();
    UUID session = UUID.randomUUID();

    String token = issuer.issue(user, session, "GOLD");

    Map<String, Object> header = part(token, 0);
    assertThat(header).containsEntry("alg", "RS256").containsEntry("kid", issuer.kid());
    Map<String, Object> claims = part(token, 1);
    assertThat(claims)
        .containsEntry("sub", user.toString())
        .containsEntry("sid", session.toString())
        .containsEntry("tier", "GOLD")
        .containsEntry("iat", (int) NOW.getEpochSecond())
        .containsEntry("exp", (int) NOW.plus(Duration.ofMinutes(10)).getEpochSecond())
        .containsEntry("iss", "mayshop-core")
        .containsEntry("aud", "mayshop");

    String[] parts = token.split("\\.");
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(pair.getPublic());
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
  }

  @Test
  void expirationFollowsTheConfiguredTtl() {
    AccessTokenIssuer issuer = issuer(TestKeys.pair(2048), Duration.ofSeconds(90));

    Map<String, Object> claims =
        part(issuer.issue(UUID.randomUUID(), UUID.randomUUID(), "STANDARD"), 1);

    assertThat(claims).containsEntry("exp", (int) NOW.getEpochSecond() + 90);
  }

  @Test
  void signatureDoesNotVerifyWithAnotherKey() throws Exception {
    AccessTokenIssuer issuer = issuer(TestKeys.pair(2048), Duration.ofMinutes(10));
    String[] parts = issuer.issue(UUID.randomUUID(), UUID.randomUUID(), "SILVER").split("\\.");

    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(TestKeys.pair(2048).getPublic());
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));

    assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isFalse();
  }

  @Test
  void sameKeyGivesTheSameKidAcrossIssuerInstancesAndDifferentKeysDiffer() {
    KeyPair pair = TestKeys.pair(2048);

    assertThat(issuer(pair, Duration.ofMinutes(10)).kid())
        .isEqualTo(issuer(pair, Duration.ofMinutes(5)).kid());
    assertThat(issuer(TestKeys.pair(2048), Duration.ofMinutes(10)).kid())
        .isNotEqualTo(issuer(pair, Duration.ofMinutes(10)).kid());
  }
}
