package com.mayshop.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Builds RS256 access tokens the way core does, with knobs for each way a token can be wrong. */
public final class Tokens {

  public static final String ISSUER = "mayshop-core";
  public static final String AUDIENCE = "mayshop";

  private Tokens() {}

  /** A fresh 2048-bit RSA key with the given key id. */
  public static RSAKey newKey(String kid) {
    try {
      return new RSAKeyGenerator(2048).keyID(kid).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public static JWKSet publicSet(RSAKey... keys) {
    JWKSet set = new JWKSet();
    java.util.List<com.nimbusds.jose.jwk.JWK> publics = new java.util.ArrayList<>();
    for (RSAKey key : keys) {
      publics.add(key.toPublicJWK());
    }
    return new JWKSet(publics);
  }

  /** A valid token for the key, expiring in ten minutes. */
  public static String valid(RSAKey key) {
    return sign(key, key.getKeyID(), ISSUER, AUDIENCE, Instant.now().plusSeconds(600));
  }

  /** Signs a token with the given private key, header kid and claims. */
  public static String sign(RSAKey key, String kid, String issuer, String audience, Instant exp) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(UUID.randomUUID().toString())
              .claim("sid", UUID.randomUUID().toString())
              .claim("tier", "STANDARD")
              .issuer(issuer)
              .audience(audience)
              .issueTime(Date.from(exp.minusSeconds(600)))
              .expirationTime(Date.from(exp))
              .build();
      SignedJWT jwt =
          new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }
}
