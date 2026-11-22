package com.mayshop.gateway.origin;

import com.mayshop.gateway.error.ProblemResponses;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * CORS for the web origin only, plus the {@code Origin} check that protects state-changing requests
 * (with {@code SameSite=Lax} cookies this is the CSRF protection).
 *
 * <p>A {@link WebFilter} with the highest precedence, so it runs before every gateway filter
 * including rate limiting: a rejected request never reaches the upstream and consumes no tokens.
 * Only {@code POST}, {@code PUT}, {@code PATCH} and {@code DELETE} are checked. They are rejected
 * when {@code Origin} is present and not the web origin (including {@code null}), or absent while
 * {@code Sec-Fetch-Site} is {@code cross-site}. Server-side callers send neither header and pass.
 */
public class OriginFilter implements WebFilter, Ordered {

  private static final Set<HttpMethod> STATE_CHANGING =
      Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
  private static final String ALLOWED_METHODS = "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS";
  private static final String ALLOWED_HEADERS = "Content-Type, Accept, Idempotency-Key";
  private static final String MAX_AGE_SECONDS = "600";

  private final String webOrigin;

  /** Creates the filter; {@code webOrigin} is compared to {@code Origin} exactly. */
  public OriginFilter(String webOrigin) {
    this.webOrigin = webOrigin;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    HttpHeaders headers = exchange.getRequest().getHeaders();
    String origin = headers.getFirst(HttpHeaders.ORIGIN);
    HttpMethod method = exchange.getRequest().getMethod();
    boolean allowed = origin != null && origin.equals(webOrigin);

    if (HttpMethod.OPTIONS.equals(method)
        && headers.containsHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)) {
      return preflight(exchange, allowed);
    }
    if (STATE_CHANGING.contains(method) && !passesOriginCheck(headers, origin, allowed)) {
      return ProblemResponses.forbidden(exchange);
    }
    if (allowed) {
      addCorsHeaders(exchange.getResponse().getHeaders());
    }
    return chain.filter(exchange);
  }

  private static boolean passesOriginCheck(HttpHeaders headers, String origin, boolean allowed) {
    if (origin != null) {
      return allowed;
    }
    return !"cross-site".equalsIgnoreCase(headers.getFirst("Sec-Fetch-Site"));
  }

  private Mono<Void> preflight(ServerWebExchange exchange, boolean allowed) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(HttpStatus.NO_CONTENT);
    if (allowed) {
      HttpHeaders out = response.getHeaders();
      addCorsHeaders(out);
      out.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, ALLOWED_METHODS);
      out.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, ALLOWED_HEADERS);
      out.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, MAX_AGE_SECONDS);
    }
    return response.setComplete();
  }

  private void addCorsHeaders(HttpHeaders out) {
    out.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, webOrigin);
    out.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    out.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
  }
}
