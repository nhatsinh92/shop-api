package com.mayshop.gateway.error;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Writes RFC 9457 problem responses with a stable {@code code} and a {@code traceId}. */
public final class ProblemResponses {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemResponses.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ProblemResponses() {}

  /** Answers 429 {@code RATE_LIMITED} with a {@code Retry-After} header in seconds. */
  public static Mono<Void> rateLimited(ServerWebExchange exchange, long retryAfterSeconds) {
    exchange
        .getResponse()
        .getHeaders()
        .set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, retryAfterSeconds)));
    return write(exchange, HttpStatus.TOO_MANY_REQUESTS, "Too many requests", "RATE_LIMITED", null);
  }

  static Mono<Void> write(
      ServerWebExchange exchange, HttpStatus status, String title, String code, Throwable error) {
    ServerHttpResponse response = exchange.getResponse();
    String traceId = UUID.randomUUID().toString().replace("-", "");
    if (status.is5xxServerError()) {
      LOG.error("Gateway error {} traceId={}", code, traceId, error);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", title);
    body.put("status", status.value());
    body.put("code", code);
    body.put("traceId", traceId);
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    DataBuffer buffer =
        response
            .bufferFactory()
            .wrap(JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
  }
}
