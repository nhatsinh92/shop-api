package com.mayshop.core.checkout;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Tokens signed by test keys, and a decoder that trusts exactly those keys. */
final class CheckoutSupport {

  static final String USER = "0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0001";
  static final String OTHER_USER = "0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0002";

  private static final KeyPair KEYS = newKeyPair();

  private CheckoutSupport() {}

  private static KeyPair newKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A signed access token for {@code subject}; a {@code null} tier leaves the claim out. */
  static String token(String subject, String tier) {
    try {
      JWTClaimsSet.Builder claims =
          new JWTClaimsSet.Builder()
              .subject(subject)
              .expirationTime(new Date(System.currentTimeMillis() + 600_000));
      if (tier != null) {
        claims.claim("tier", tier);
      }
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
      jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static class JwtConfiguration {

    @Bean
    @Primary
    JwtDecoder testKeyDecoder() {
      return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
    }
  }
}
