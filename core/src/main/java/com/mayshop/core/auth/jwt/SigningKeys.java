package com.mayshop.core.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The key used to sign access tokens, plus an optional previous public key for rotation. */
public final class SigningKeys {

  private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

  private final RSAPrivateKey privateKey;
  private final RSAPublicKey publicKey;
  private final RSAPublicKey previousPublicKey;

  SigningKeys(RSAPrivateKey privateKey, RSAPublicKey publicKey, RSAPublicKey previousPublicKey) {
    this.privateKey = privateKey;
    this.publicKey = publicKey;
    this.previousPublicKey = previousPublicKey;
  }

  RSAPrivateKey privateKey() {
    return privateKey;
  }

  /** The id of the signing key, stable for the same public key. */
  public String kid() {
    return kidOf(publicKey);
  }

  /** Public keys as JWK maps: the current key first, then the previous one when configured. */
  List<Map<String, String>> jwks() {
    List<Map<String, String>> keys = new ArrayList<>();
    keys.add(jwk(publicKey));
    if (previousPublicKey != null) {
      keys.add(jwk(previousPublicKey));
    }
    return keys;
  }

  static String kidOf(RSAPublicKey key) {
    // RFC 7638 JWK thumbprint: SHA-256 over the required members in lexicographic order.
    String json =
        "{\"e\":\""
            + b64(key.getPublicExponent())
            + "\",\"kty\":\"RSA\",\"n\":\""
            + b64(key.getModulus())
            + "\"}";
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
      return B64URL.encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, String> jwk(RSAPublicKey key) {
    Map<String, String> jwk = new LinkedHashMap<>();
    jwk.put("kty", "RSA");
    jwk.put("kid", kidOf(key));
    jwk.put("use", "sig");
    jwk.put("alg", "RS256");
    jwk.put("n", b64(key.getModulus()));
    jwk.put("e", b64(key.getPublicExponent()));
    return jwk;
  }

  private static String b64(java.math.BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return B64URL.encodeToString(bytes);
  }
}
