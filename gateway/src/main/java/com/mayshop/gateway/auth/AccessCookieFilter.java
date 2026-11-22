package com.mayshop.gateway.auth;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Builds {@code Authorization} from the {@code access_token} cookie and nothing else.
 *
 * <p>A client-sent {@code Authorization} header is always removed. A valid cookie is forwarded as
 * {@code Bearer}; an invalid one is removed from the {@code Cookie} header and counted, so the
 * request goes upstream exactly like one without the cookie. The gateway never answers 401. The
 * login, refresh and logout routes keep their cookies untouched so an expired session can always be
 * refreshed or cleared.
 */
public class AccessCookieFilter implements GlobalFilter, Ordered {

  /** Name of the metric counting stripped tokens. */
  public static final String INVALID_METRIC = "gateway.auth.token.invalid";

  static final String COOKIE_NAME = "access_token";
  private static final Logger LOG = LoggerFactory.getLogger(AccessCookieFilter.class);
  private static final Set<String> EXEMPT_PATHS =
      Set.of("/api/auth/login", "/api/auth/refresh", "/api/auth/logout");

  private final AccessTokenVerifier verifier;
  private final MeterRegistry meters;

  /** Creates the filter. */
  public AccessCookieFilter(AccessTokenVerifier verifier, MeterRegistry meters) {
    this.verifier = verifier;
    this.meters = meters;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    if (EXEMPT_PATHS.contains(request.getPath().value())) {
      return chain.filter(withRequest(exchange, request, null, false));
    }
    List<String> tokens = accessTokens(request.getHeaders().get(HttpHeaders.COOKIE));
    if (tokens.isEmpty()) {
      return chain.filter(withRequest(exchange, request, null, false));
    }
    Mono<AccessTokenVerifier.Result> outcome =
        tokens.size() > 1
            ? Mono.just(new AccessTokenVerifier.Result(InvalidReason.MALFORMED))
            : verifier.verify(tokens.get(0));
    return outcome.flatMap(result -> {
      if (result.valid()) {
        return chain.filter(withRequest(exchange, request, tokens.get(0), false));
      }
      meters.counter(INVALID_METRIC, "reason", result.reason().tag()).increment();
      LOG.warn("Stripped an invalid access token: reason={}", result.reason().tag());
      return chain.filter(withRequest(exchange, request, null, true));
    });
  }

  private static ServerWebExchange withRequest(
      ServerWebExchange exchange, ServerHttpRequest request, String bearer, boolean stripCookie) {
    ServerHttpRequest mutated =
        request
            .mutate()
            .headers(headers -> {
              headers.remove(HttpHeaders.AUTHORIZATION);
              if (bearer != null) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
              }
              if (stripCookie) {
                List<String> kept = withoutAccessCookie(headers.get(HttpHeaders.COOKIE));
                headers.remove(HttpHeaders.COOKIE);
                if (!kept.isEmpty()) {
                  headers.set(HttpHeaders.COOKIE, String.join("; ", kept));
                }
              }
            })
            .build();
    return exchange.mutate().request(mutated).build();
  }

  private static List<String> accessTokens(List<String> cookieHeaders) {
    List<String> tokens = new ArrayList<>();
    if (cookieHeaders == null) {
      return tokens;
    }
    for (String header : cookieHeaders) {
      for (String pair : header.split(";")) {
        String trimmed = pair.trim();
        if (trimmed.startsWith(COOKIE_NAME + "=")) {
          tokens.add(trimmed.substring(COOKIE_NAME.length() + 1));
        }
      }
    }
    return tokens;
  }

  private static List<String> withoutAccessCookie(List<String> cookieHeaders) {
    List<String> kept = new ArrayList<>();
    if (cookieHeaders == null) {
      return kept;
    }
    for (String header : cookieHeaders) {
      for (String pair : header.split(";")) {
        String trimmed = pair.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith(COOKIE_NAME + "=")) {
          kept.add(trimmed);
        }
      }
    }
    return kept;
  }
}
