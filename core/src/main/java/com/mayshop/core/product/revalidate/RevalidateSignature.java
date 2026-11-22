package com.mayshop.core.product.revalidate;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Builds the {@code X-Shop-Signature} header: {@code t=<unix seconds>,v1=<hex HMAC-SHA256>}. */
final class RevalidateSignature {

  private static final String ALGORITHM = "HmacSHA256";

  private RevalidateSignature() {}

  /** The header value for {@code body} signed at {@code epochSeconds} (HMAC over "t.body"). */
  static String header(String secret, long epochSeconds, byte[] body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      mac.update((epochSeconds + ".").getBytes(StandardCharsets.UTF_8));
      mac.update(body);
      return "t=" + epochSeconds + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 is not available", e);
    }
  }
}
