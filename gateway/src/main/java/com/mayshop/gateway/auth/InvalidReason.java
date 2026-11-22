package com.mayshop.gateway.auth;

/** Why the gateway stripped an access token; the lower-case name is the metric tag value. */
public enum InvalidReason {
  SIGNATURE,
  UNKNOWN_KID,
  ISSUER,
  AUDIENCE,
  EXPIRED,
  MALFORMED;

  /** The value of the {@code reason} tag. */
  public String tag() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
