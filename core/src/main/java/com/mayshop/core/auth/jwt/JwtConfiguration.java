package com.mayshop.core.auth.jwt;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Loads the signing key at startup; a malformed key fails the application context. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(JwtConfiguration.class);

  @Bean
  SigningKeys signingKeys(JwtProperties properties) {
    RSAPublicKey previous =
        StringUtils.hasText(properties.previousPublicKey())
            ? PemKeys.publicKey(properties.previousPublicKey(), "AUTH_JWT_PREVIOUS_PUBLIC_KEY")
            : null;
    if (StringUtils.hasText(properties.privateKey())) {
      RSAPrivateCrtKey key = PemKeys.privateKey(properties.privateKey(), "AUTH_JWT_PRIVATE_KEY");
      return new SigningKeys(key, PemKeys.publicFrom(key), previous);
    }
    LOG.warn(
        "AUTH_JWT_PRIVATE_KEY is not set: using an ephemeral signing key, tokens will not survive a restart");
    KeyPair pair = generate();
    return new SigningKeys(
        (RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic(), previous);
  }

  @Bean
  AccessTokenIssuer accessTokenIssuer(SigningKeys keys, JwtProperties properties) {
    return new AccessTokenIssuer(keys, properties, Clock.systemUTC());
  }

  private static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(PemKeys.MIN_BITS);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
