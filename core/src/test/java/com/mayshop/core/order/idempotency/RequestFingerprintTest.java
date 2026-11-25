package com.mayshop.core.order.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The fingerprint ignores key order and whitespace and covers the mock-outcome header. */
class RequestFingerprintTest {

  private static final String BODY = "{\"a\":1,\"b\":{\"x\":[1,2],\"y\":\"z\"}}";

  @Test
  void keyOrderDoesNotMatter() {
    assertThat(RequestFingerprint.of("{\"b\":{\"y\":\"z\",\"x\":[1,2]},\"a\":1}", null))
        .isEqualTo(RequestFingerprint.of(BODY, null));
  }

  @Test
  void whitespaceDoesNotMatter() {
    String spaced = "{ \"a\" : 1,\n  \"b\" : { \"x\" : [ 1, 2 ], \"y\" : \"z\" } }";
    assertThat(RequestFingerprint.of(spaced, null)).isEqualTo(RequestFingerprint.of(BODY, null));
  }

  @Test
  void arrayOrderMatters() {
    assertThat(RequestFingerprint.of("[1,2]", null))
        .isNotEqualTo(RequestFingerprint.of("[2,1]", null));
  }

  @Test
  void aDifferentValueChangesTheFingerprint() {
    assertThat(RequestFingerprint.of("{\"a\":1}", null))
        .isNotEqualTo(RequestFingerprint.of("{\"a\":2}", null));
  }

  @Test
  void theMockOutcomeHeaderIsPartOfTheFingerprint() {
    assertThat(RequestFingerprint.of(BODY, "fail"))
        .isNotEqualTo(RequestFingerprint.of(BODY, null))
        .isNotEqualTo(RequestFingerprint.of(BODY, "timeout"));
  }

  @Test
  void anAbsentHeaderEqualsAnEmptyOne() {
    assertThat(RequestFingerprint.of(BODY, "")).isEqualTo(RequestFingerprint.of(BODY, null));
  }

  @Test
  void isALowercaseSha256Hex() {
    assertThat(RequestFingerprint.of(BODY, null)).matches("[0-9a-f]{64}");
  }
}
