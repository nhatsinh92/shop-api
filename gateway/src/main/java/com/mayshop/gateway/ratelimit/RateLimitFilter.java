package com.mayshop.gateway.ratelimit;

import com.mayshop.gateway.error.ProblemResponses;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rate-limits {@code /api/auth/login}, {@code /api/auth/refresh} and the other {@code core} routes;
 * {@code POST /api/checkout/reservations} and {@code POST /api/orders} also have a per-user window.
 * {@code /api/cart/**} is never limited here (technical notes § 7: limits sit at checkout).
 *
 * <p>Runs right after {@code AccessCookieFilter}, so an {@code Authorization} header is present
 * only when the access cookie was verified; its {@code sub} keys the core bucket. The client IP is
 * resolved by {@link ClientIpResolver}: {@code X-Forwarded-For} counts only from trusted proxies.
 */
public class RateLimitFilter implements GlobalFilter, Ordered {

  private static final String CART_PREFIX = "/api/cart";
  private static final String LOGIN = "/api/auth/login";
  private static final String REFRESH = "/api/auth/refresh";
  private static final String CHECKOUT_RESERVATIONS = "/api/checkout/reservations";
  private static final String ORDERS = "/api/orders";
  private static final String REFRESH_COOKIE = "refresh_token";
  private static final int MAX_LOGIN_BODY_BYTES = 64 * 1024;

  private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

  private final RateLimiter limiter;
  private final RateLimitProperties limits;
  private final JsonMapper json;
  private final ClientIpResolver clientIps;

  /** Creates the filter. */
  public RateLimitFilter(
      RateLimiter limiter,
      RateLimitProperties limits,
      JsonMapper json,
      ClientIpResolver clientIps) {
    this.limiter = limiter;
    this.limits = limits;
    this.json = json;
    this.clientIps = clientIps;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    String path = request.getPath().value();
    if (path.equals(CART_PREFIX) || path.startsWith(CART_PREFIX + "/")) {
      return chain.filter(exchange);
    }
    if (!path.startsWith("/api/")) {
      return chain.filter(exchange);
    }
    String ip = clientIps.resolve(request);
    LOG.debug("Client IP {} for {} {}", ip, request.getMethod(), path);
    if (path.equals(LOGIN) && HttpMethod.POST.equals(request.getMethod())) {
      return login(exchange, chain, ip);
    }
    if (path.equals(REFRESH) && HttpMethod.POST.equals(request.getMethod())) {
      return refresh(exchange, chain);
    }
    return core(exchange, chain, ip);
  }

  private Mono<Void> login(ServerWebExchange exchange, GatewayFilterChain chain, String ip) {
    return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_LOGIN_BODY_BYTES)
        .map(buffer -> {
          byte[] bytes = new byte[buffer.readableByteCount()];
          buffer.read(bytes);
          DataBufferUtils.release(buffer);
          return bytes;
        })
        .defaultIfEmpty(new byte[0])
        .flatMap(body -> {
          ServerWebExchange replay = withBody(exchange, body);
          String email = normalisedEmail(body);
          Mono<RateLimiter.Decision> byEmail =
              email == null
                  ? Mono.just(RateLimiter.Decision.permit())
                  : limiter.perMinute(
                      "login:email:" + sha256(email), limits.loginPerEmailPerMinute());
          return byEmail
              .flatMap(decision ->
                  decision.allowed()
                      ? limiter.perMinute("login:ip:" + ip, limits.loginPerIpPerMinute())
                      : Mono.just(decision))
              .flatMap(decision -> proceed(decision, replay, chain));
        });
  }

  private Mono<Void> refresh(ServerWebExchange exchange, GatewayFilterChain chain) {
    HttpCookie cookie = exchange.getRequest().getCookies().getFirst(REFRESH_COOKIE);
    if (cookie == null || cookie.getValue().isEmpty()) {
      return chain.filter(exchange);
    }
    return limiter
        .perMinute("refresh:" + sha256(cookie.getValue()), limits.refreshPerSessionPerMinute())
        .flatMap(decision -> proceed(decision, exchange, chain));
  }

  private Mono<Void> core(ServerWebExchange exchange, GatewayFilterChain chain, String ip) {
    String subject = subject(exchange.getRequest().getHeaders());
    String key = subject != null ? "core:sub:" + subject : "core:ip:" + ip;
    Mono<RateLimiter.Decision> coreDecision =
        limiter.bucket(key, limits.coreRate(), limits.coreBurst());
    Mono<RateLimiter.Decision> checkout = checkoutDecision(exchange.getRequest(), subject);
    return coreDecision
        .flatMap(decision -> decision.allowed() ? checkout : Mono.just(decision))
        .flatMap(decision -> proceed(decision, exchange, chain));
  }

  /**
   * The per-user window of the two state-changing checkout operations, counted only for requests
   * the core bucket let through. Other methods and paths, and requests without a verified {@code
   * sub}, are not counted.
   */
  private Mono<RateLimiter.Decision> checkoutDecision(ServerHttpRequest request, String subject) {
    if (subject == null || !HttpMethod.POST.equals(request.getMethod())) {
      return Mono.just(RateLimiter.Decision.permit());
    }
    String path = request.getPath().value();
    if (path.equals(CHECKOUT_RESERVATIONS)) {
      return limiter.perMinute(
          "checkout:reservation:sub:" + subject, limits.checkoutReservationRate());
    }
    if (path.equals(ORDERS)) {
      return limiter.perMinute("checkout:order:sub:" + subject, limits.orderRate());
    }
    return Mono.just(RateLimiter.Decision.permit());
  }

  private static Mono<Void> proceed(
      RateLimiter.Decision decision, ServerWebExchange exchange, GatewayFilterChain chain) {
    if (decision.allowed()) {
      return chain.filter(exchange);
    }
    return ProblemResponses.rateLimited(exchange, decision.retryAfterSeconds());
  }

  private String normalisedEmail(byte[] body) {
    if (body.length == 0) {
      return null;
    }
    try {
      JsonNode email = json.readTree(body).path("email");
      if (!email.isString()) {
        return null;
      }
      String normalised = email.asString().trim().toLowerCase(Locale.ROOT);
      return normalised.isEmpty() ? null : normalised;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The {@code sub} of the bearer token set by the access cookie filter, which verified it. */
  private static String subject(HttpHeaders headers) {
    String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return null;
    }
    try {
      String sub = SignedJWT.parse(authorization.substring(7)).getJWTClaimsSet().getSubject();
      return sub == null || sub.isBlank() ? null : sub;
    } catch (java.text.ParseException | RuntimeException e) {
      return null;
    }
  }

  private static ServerWebExchange withBody(ServerWebExchange exchange, byte[] body) {
    ServerHttpRequest replay =
        new ServerHttpRequestDecorator(exchange.getRequest()) {
          @Override
          public Flux<DataBuffer> getBody() {
            return body.length == 0
                ? Flux.empty()
                : Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
          }
        };
    return exchange.mutate().request(replay).build();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
