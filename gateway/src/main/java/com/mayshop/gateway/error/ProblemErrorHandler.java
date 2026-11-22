package com.mayshop.gateway.error;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers every error the gateway itself produces as {@code application/problem+json} (RFC 9457)
 * with a stable {@code code} from {@code errors.yaml} and a {@code traceId}.
 */
@Component
@Order(-2)
public class ProblemErrorHandler implements WebExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorHandler.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
    ServerHttpResponse response = exchange.getResponse();
    if (response.isCommitted()) {
      return Mono.error(error);
    }
    Problem problem = classify(error);
    String traceId = UUID.randomUUID().toString().replace("-", "");
    if (problem.status().is5xxServerError()) {
      LOG.error("Gateway error {} traceId={}", problem.code(), traceId, error);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", problem.title());
    body.put("status", problem.status().value());
    body.put("code", problem.code());
    body.put("traceId", traceId);
    response.setStatusCode(problem.status());
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    DataBuffer buffer =
        response
            .bufferFactory()
            .wrap(JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
  }

  private static Problem classify(Throwable error) {
    if (error instanceof ResponseStatusException rse) {
      HttpStatusCode status = rse.getStatusCode();
      if (status.value() == 404) {
        return new Problem(HttpStatus.NOT_FOUND, "Not found", "NOT_FOUND");
      }
      if (status.value() == 503 || status.value() == 504) {
        return unavailable();
      }
    }
    for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof ConnectException
          || t instanceof TimeoutException
          || t instanceof java.net.UnknownHostException
          || t.getClass().getName().endsWith("ConnectTimeoutException")
          || t.getClass().getName().endsWith("ReadTimeoutException")) {
        return unavailable();
      }
    }
    return new Problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "INTERNAL_ERROR");
  }

  private static Problem unavailable() {
    return new Problem(
        HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", "SERVICE_UNAVAILABLE");
  }

  private record Problem(HttpStatus status, String title, String code) {}
}
