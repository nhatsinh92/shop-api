package com.mayshop.core.product.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks {@code X-Internal-Token} against the configured {@code INTERNAL_API_TOKEN}. With no token
 * configured nothing matches.
 */
@Component
class InternalToken {

  static final String HEADER = "X-Internal-Token";

  private final byte[] tokenDigest;

  InternalToken(@Value("${product.internal.api-token:}") String token) {
    this.tokenDigest = token == null || token.isBlank() ? null : digest(token);
  }

  boolean matches(String given) {
    if (tokenDigest == null || given == null) {
      return false;
    }
    // Digests have equal length whatever the input, and isEqual compares in constant time.
    return MessageDigest.isEqual(tokenDigest, digest(given));
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
