package com.mayshop.core.auth.jwt;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Parses PEM encoded RSA keys with the JDK only. Failures carry a message fit for a startup log.
 */
final class PemKeys {

  static final int MIN_BITS = 2048;

  private PemKeys() {}

  /** Reads a PKCS#8 RSA private key ({@code BEGIN PRIVATE KEY}). */
  static RSAPrivateCrtKey privateKey(String pem, String property) {
    byte[] der = decode(pem, "PRIVATE KEY", property);
    try {
      PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
      if (key instanceof RSAPrivateCrtKey rsa) {
        requireSize(rsa.getModulus(), property);
        return rsa;
      }
    } catch (GeneralSecurityException e) {
      throw invalid(property, "it is not a valid PKCS#8 RSA private key", e);
    }
    throw invalid(property, "it is not an RSA private key with CRT parameters", null);
  }

  /** Reads an X.509 RSA public key ({@code BEGIN PUBLIC KEY}). */
  static RSAPublicKey publicKey(String pem, String property) {
    byte[] der = decode(pem, "PUBLIC KEY", property);
    try {
      PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
      if (key instanceof RSAPublicKey rsa) {
        requireSize(rsa.getModulus(), property);
        return rsa;
      }
    } catch (GeneralSecurityException e) {
      throw invalid(property, "it is not a valid X.509 RSA public key", e);
    }
    throw invalid(property, "it is not an RSA public key", null);
  }

  /** Derives the public half of a private key. */
  static RSAPublicKey publicFrom(RSAPrivateCrtKey key) {
    try {
      return (RSAPublicKey)
          KeyFactory.getInstance("RSA")
              .generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot derive the public key", e);
    }
  }

  private static void requireSize(BigInteger modulus, String property) {
    if (modulus.bitLength() < MIN_BITS) {
      throw invalid(
          property, "the key has " + modulus.bitLength() + " bits, minimum is " + MIN_BITS, null);
    }
  }

  private static byte[] decode(String pem, String label, String property) {
    String begin = "-----BEGIN " + label + "-----";
    String end = "-----END " + label + "-----";
    String text = pem.strip();
    if (!text.startsWith(begin) || !text.endsWith(end)) {
      throw invalid(property, "expected a PEM block labelled " + label, null);
    }
    String body =
        text.substring(begin.length(), text.length() - end.length()).replaceAll("\\s", "");
    try {
      return Base64.getDecoder().decode(body);
    } catch (IllegalArgumentException e) {
      throw invalid(property, "the PEM body is not valid Base64", e);
    }
  }

  private static IllegalStateException invalid(String property, String reason, Exception cause) {
    return new IllegalStateException("Invalid " + property + ": " + reason, cause);
  }
}
