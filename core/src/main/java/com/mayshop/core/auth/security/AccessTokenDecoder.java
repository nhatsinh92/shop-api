package com.mayshop.core.auth.security;

import com.mayshop.core.auth.internal.UserAccounts;
import com.mayshop.core.auth.jwt.JwtProperties;
import com.mayshop.core.auth.jwt.SigningKeys;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies access tokens locally with the public keys of {@link SigningKeys}: signature (by {@code
 * kid}, RS256 only), {@code exp}, {@code iss}, {@code aud}, and that the user still exists. No
 * network call is made.
 */
final class AccessTokenDecoder {

  private AccessTokenDecoder() {}

  static JwtDecoder create(SigningKeys keys, JwtProperties properties, UserAccounts users) {
    List<com.nimbusds.jose.jwk.JWK> jwks = new ArrayList<>();
    for (RSAPublicKey key : keys.verificationKeys()) {
      jwks.add(new RSAKey.Builder(key).keyID(SigningKeys.kidOf(key)).build());
    }
    // Only RS256: a token with another algorithm (including "none") is rejected before any key use.
    JWSKeySelector<SecurityContext> selector =
        new JWSVerificationKeySelector<>(
            JWSAlgorithm.RS256, new ImmutableJWKSet<>(new JWKSet(jwks)));
    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(selector);
    NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(),
            new JwtIssuerValidator(properties.issuer()),
            audience(properties.audience()),
            userExists(users)));
    return decoder;
  }

  private static OAuth2TokenValidator<Jwt> audience(String expected) {
    return jwt ->
        jwt.getAudience() != null && jwt.getAudience().contains(expected)
            ? OAuth2TokenValidatorResult.success()
            : failure("The audience is not accepted");
  }

  private static OAuth2TokenValidator<Jwt> userExists(UserAccounts users) {
    return jwt -> {
      try {
        UUID id = UUID.fromString(jwt.getSubject());
        return users.findById(id).isPresent()
            ? OAuth2TokenValidatorResult.success()
            : failure("The user no longer exists");
      } catch (IllegalArgumentException | NullPointerException e) {
        return failure("The subject is not a user id");
      }
    };
  }

  private static OAuth2TokenValidatorResult failure(String description) {
    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
  }
}
