package com.mayshop.gateway.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies an access token (RS256, known {@code kid}, {@code iss}, {@code aud}, {@code exp}) and
 * names the reason when it is not valid. Never throws: every failure is an {@link InvalidReason}.
 */
public class AccessTokenVerifier {

  /** Outcome of a verification: a valid token, or the reason it was rejected. */
  public record Result(InvalidReason reason) {
    /** True when the token passed every check. */
    public boolean valid() {
      return reason == null;
    }
  }

  private static final Result VALID = new Result(null);

  private final JwksKeySource keys;
  private final ReactiveJwtDecoder decoder;

  /** Creates a verifier that checks tokens against the keys and the issuer and audience given. */
  public AccessTokenVerifier(JwksKeySource keys, GatewayAuthProperties properties, Clock clock) {
    this.keys = keys;
    NimbusReactiveJwtDecoder nimbus =
        NimbusReactiveJwtDecoder.withJwkSource(
                jwt -> Flux.from(keys.find(jwt.getHeader().getKeyID())))
            .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
            .build();
    // core's issuer is a plain name, not a URL: keep the claim a String instead of converting it.
    nimbus.setClaimSetConverter(
        MappedJwtClaimSetConverter.withDefaults(
            Map.of("iss", value -> value == null ? null : value.toString())));
    nimbus.setJwtValidator(validator(properties, clock));
    this.decoder = nimbus;
  }

  /** Verifies the compact token. */
  public Mono<Result> verify(String token) {
    SignedJWT parsed;
    try {
      parsed = SignedJWT.parse(token);
    } catch (ParseException | RuntimeException e) {
      return Mono.just(new Result(InvalidReason.MALFORMED));
    }
    if (!JWSAlgorithm.RS256.equals(parsed.getHeader().getAlgorithm())) {
      return Mono.just(new Result(InvalidReason.SIGNATURE));
    }
    String kid = parsed.getHeader().getKeyID();
    if (kid == null) {
      return Mono.just(new Result(InvalidReason.UNKNOWN_KID));
    }
    return keys.find(kid)
        .flatMap(
            key -> decoder.decode(token).thenReturn(VALID).onErrorResume(AccessTokenVerifier::fail))
        .switchIfEmpty(Mono.just(new Result(InvalidReason.UNKNOWN_KID)));
  }

  private static Mono<Result> fail(Throwable error) {
    if (error instanceof JwtValidationException validation) {
      for (OAuth2Error e : validation.getErrors()) {
        for (InvalidReason reason : InvalidReason.values()) {
          if (reason.tag().equals(e.getErrorCode())) {
            return Mono.just(new Result(reason));
          }
        }
      }
    }
    // Signature mismatch and any other decoder failure: the token cannot be trusted.
    return Mono.just(new Result(InvalidReason.SIGNATURE));
  }

  private static OAuth2TokenValidator<Jwt> validator(GatewayAuthProperties p, Clock clock) {
    return jwt -> {
      Instant exp = jwt.getExpiresAt();
      if (exp == null || !exp.isAfter(clock.instant())) {
        return failure(InvalidReason.EXPIRED, "The token is expired or has no exp");
      }
      if (!p.issuer().equals(jwt.getClaimAsString("iss"))) {
        return failure(InvalidReason.ISSUER, "Wrong issuer");
      }
      List<String> audience = jwt.getAudience();
      if (audience == null || !audience.contains(p.audience())) {
        return failure(InvalidReason.AUDIENCE, "Wrong audience");
      }
      return OAuth2TokenValidatorResult.success();
    };
  }

  private static OAuth2TokenValidatorResult failure(InvalidReason reason, String description) {
    return OAuth2TokenValidatorResult.failure(new OAuth2Error(reason.tag(), description, null));
  }
}
