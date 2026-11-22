package com.mayshop.gateway.error;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Answers every error the gateway itself produces as {@code application/problem+json} (RFC 9457)
 * with a stable {@code code} from {@code errors.yaml} and a {@code traceId}.
 */
@Component
@Order(-2)
public class ProblemErrorHandler implements WebExceptionHandler {

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(error);
    }
    Problem problem = classify(error);
    return ProblemResponses.write(
        exchange, problem.status(), problem.title(), problem.code(), error);
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
