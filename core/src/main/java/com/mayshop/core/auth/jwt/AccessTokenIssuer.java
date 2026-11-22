package com.mayshop.core.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Creates RS256 access tokens. Used by login and refresh inside the {@code auth} module. */
public final class AccessTokenIssuer {

  private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final SigningKeys keys;
  private final JwtProperties properties;
  private final Clock clock;

  AccessTokenIssuer(SigningKeys keys, JwtProperties properties, Clock clock) {
    this.keys = keys;
    this.properties = properties;
    this.clock = clock;
  }

  /** The key id written to the token header. */
  public String kid() {
    return keys.kid();
  }

  /** Lifetime of the tokens, also the {@code Max-Age} of the access cookie. */
  public java.time.Duration ttl() {
    return properties.accessTokenTtl();
  }

  /** Issues a token for a user and session; {@code tier} is the member tier name. */
  public String issue(UUID userId, UUID sessionId, String tier) {
    Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", "RS256");
    header.put("typ", "JWT");
    header.put("kid", keys.kid());
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("sub", userId.toString());
    claims.put("sid", sessionId.toString());
    claims.put("tier", tier);
    claims.put("iat", now.getEpochSecond());
    claims.put("exp", now.plus(properties.accessTokenTtl()).getEpochSecond());
    claims.put("iss", properties.issuer());
    claims.put("aud", properties.audience());
    String signingInput = encode(header) + "." + encode(claims);
    return signingInput + "." + B64URL.encodeToString(sign(signingInput));
  }

  private static String encode(Map<String, Object> json) {
    return B64URL.encodeToString(JSON.writeValueAsString(json).getBytes(StandardCharsets.UTF_8));
  }

  private byte[] sign(String input) {
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(keys.privateKey());
      signature.update(input.getBytes(StandardCharsets.US_ASCII));
      return signature.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot sign the access token", e);
    }
  }
}
