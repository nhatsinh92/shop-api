package com.mayshop.gateway.auth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Public keys of core, fetched lazily from the JWKS endpoint and cached.
 *
 * <p>A fetched set is trusted for {@code jwksTtl}. An unknown {@code kid} triggers a refetch, but
 * at most one per {@code jwksMinRefetch}, so random key ids cannot flood core. A failed fetch is
 * retried after {@code jwksFailureBackoff}. Concurrent lookups share one in-flight request.
 */
public class JwksKeySource {

  private static final Logger LOG = LoggerFactory.getLogger(JwksKeySource.class);

  private final WebClient client;
  private final GatewayAuthProperties properties;
  private final Clock clock;

  private JWKSet keys;
  private Instant fetchedAt;
  private Instant lastAttempt;
  private Mono<JWKSet> inFlight;

  /** Creates a key source for the JWKS URI in {@code properties}. */
  public JwksKeySource(WebClient client, GatewayAuthProperties properties, Clock clock) {
    this.client = client;
    this.properties = properties;
    this.clock = clock;
  }

  /** Finds the key with the given id, fetching the key set when needed; empty when unknown. */
  public Mono<JWK> find(String kid) {
    Mono<JWKSet> source;
    synchronized (this) {
      Instant now = clock.instant();
      if (keys != null && isFresh(now) && keys.getKeyByKeyId(kid) != null) {
        return Mono.just(keys.getKeyByKeyId(kid));
      }
      if (inFlight != null) {
        source = inFlight;
      } else if (mayFetch(now)) {
        source = startFetch(now);
      } else {
        return Mono.justOrEmpty(keys == null ? null : keys.getKeyByKeyId(kid));
      }
    }
    return source
        .flatMap(set -> Mono.justOrEmpty(set.getKeyByKeyId(kid)))
        .onErrorResume(e -> Mono.empty());
  }

  private boolean isFresh(Instant now) {
    return fetchedAt != null
        && Duration.between(fetchedAt, now).compareTo(properties.jwksTtl()) < 0;
  }

  private boolean mayFetch(Instant now) {
    if (lastAttempt == null) {
      return true;
    }
    Duration wait = keys == null ? properties.jwksFailureBackoff() : properties.jwksMinRefetch();
    return Duration.between(lastAttempt, now).compareTo(wait) >= 0;
  }

  private Mono<JWKSet> startFetch(Instant now) {
    lastAttempt = now;
    Mono<JWKSet> fetch =
        client
            .get()
            .uri(properties.jwksUri())
            .retrieve()
            .bodyToMono(String.class)
            .timeout(properties.jwksTimeout())
            .map(JwksKeySource::parse)
            .doOnNext(this::store)
            .doOnError(
                e -> LOG.warn("JWKS fetch from {} failed: {}", properties.jwksUri(), e.toString()))
            .doOnTerminate(this::clearInFlight)
            .doOnCancel(this::clearInFlight)
            .cache();
    inFlight = fetch;
    return fetch;
  }

  private static JWKSet parse(String body) {
    try {
      return JWKSet.parse(body);
    } catch (java.text.ParseException e) {
      throw new IllegalStateException("The JWKS response is not a key set", e);
    }
  }

  private synchronized void store(JWKSet set) {
    keys = set;
    fetchedAt = clock.instant();
  }

  private synchronized void clearInFlight() {
    inFlight = null;
  }
}
