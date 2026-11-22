package com.mayshop.gateway.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-token verification settings, bound from {@code gateway.auth}.
 *
 * @param issuer expected {@code iss} claim ({@code AUTH_JWT_ISSUER}, the same default as core)
 * @param audience expected {@code aud} claim ({@code AUTH_JWT_AUDIENCE}, the same default as core)
 * @param jwksUri where core publishes its public keys
 * @param jwksTtl how long a fetched key set is trusted
 * @param jwksMinRefetch minimum time between two fetches triggered by an unknown {@code kid}
 * @param jwksFailureBackoff minimum time between two fetches after a failed fetch
 * @param jwksTimeout timeout of one JWKS request
 */
@ConfigurationProperties(prefix = "gateway.auth")
public record GatewayAuthProperties(
    String issuer,
    String audience,
    String jwksUri,
    Duration jwksTtl,
    Duration jwksMinRefetch,
    Duration jwksFailureBackoff,
    Duration jwksTimeout) {

  /** Applies the defaults for settings left unset. */
  public GatewayAuthProperties {
    issuer = issuer == null || issuer.isBlank() ? "mayshop-core" : issuer;
    audience = audience == null || audience.isBlank() ? "mayshop" : audience;
    jwksUri =
        jwksUri == null || jwksUri.isBlank()
            ? "http://localhost:8081/.well-known/jwks.json"
            : jwksUri;
    jwksTtl = jwksTtl == null ? Duration.ofMinutes(5) : jwksTtl;
    jwksMinRefetch = jwksMinRefetch == null ? Duration.ofSeconds(30) : jwksMinRefetch;
    jwksFailureBackoff = jwksFailureBackoff == null ? Duration.ofSeconds(5) : jwksFailureBackoff;
    jwksTimeout = jwksTimeout == null ? Duration.ofSeconds(2) : jwksTimeout;
  }
}
