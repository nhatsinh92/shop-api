package com.mayshop.gateway.routing;

import com.mayshop.gateway.error.ProblemResponses;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Keeps path tricks and the internal token away from the upstreams.
 *
 * <p>Only {@code /api/**} is routed, but an upstream may normalise the path it receives, so {@code
 * /api/../internal/x} (or its encoded forms) must never be forwarded. Any request whose raw path
 * has a dot segment, an encoded slash or dot-dot, a backslash, a double-encoded character or an
 * empty segment is answered 404 {@code NOT_FOUND} by the gateway. The {@code X-Internal-Token}
 * header belongs to service-to-service calls and is removed from every forwarded request.
 *
 * <p>Runs right after the {@code OriginFilter}, before every gateway filter.
 */
@Component
public class PathGuardFilter implements WebFilter, Ordered {

  static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    if (isSuspicious(request.getURI().getRawPath())) {
      return ProblemResponses.notFound(exchange);
    }
    if (request.getHeaders().containsHeader(INTERNAL_TOKEN_HEADER)) {
      ServerHttpRequest cleaned =
          request.mutate().headers(headers -> headers.remove(INTERNAL_TOKEN_HEADER)).build();
      return chain.filter(exchange.mutate().request(cleaned).build());
    }
    return chain.filter(exchange);
  }

  static boolean isSuspicious(String rawPath) {
    if (rawPath == null || rawPath.isEmpty()) {
      return false;
    }
    String lower = rawPath.toLowerCase(Locale.ROOT);
    if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%25")) {
      return true;
    }
    if (lower.indexOf('\\') >= 0 || lower.contains("//")) {
      return true;
    }
    for (String segment : lower.split("/", -1)) {
      String decoded = segment.replace("%2e", ".");
      if (decoded.equals(".") || decoded.equals("..")) {
        return true;
      }
    }
    return false;
  }
}
