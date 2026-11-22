package com.mayshop.core.auth.jwt;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/** Generates keys for tests; nothing is committed. */
final class TestKeys {

  private TestKeys() {}

  static KeyPair pair(int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String privatePem(KeyPair pair) {
    return pem("PRIVATE KEY", pair.getPrivate().getEncoded());
  }

  static String publicPem(KeyPair pair) {
    return pem("PUBLIC KEY", pair.getPublic().getEncoded());
  }

  private static String pem(String label, byte[] der) {
    String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
    return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
  }
}
