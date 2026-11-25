package com.mayshop.core.order;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Test tokens and a fixed clock for the order endpoint tests. */
final class OrderSupport {

  static final String USER = "0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0011";
  static final String OTHER_USER = "0b6f6d0e-6c1d-4f43-9d5e-1a0e5f1a0012";
  static final Instant NOW = Instant.parse("2026-12-12T03:00:00Z");

  private static final KeyPair KEYS = newKeyPair();

  private OrderSupport() {}

  private static KeyPair newKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String token(String subject) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject(subject)
              .claim("tier", "GOLD")
              .expirationTime(new Date(System.currentTimeMillis() + 600_000))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static class Configuration {

    @Bean
    @Primary
    JwtDecoder testKeyDecoder() {
      return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
    }

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
