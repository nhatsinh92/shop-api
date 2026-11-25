package com.mayshop.gateway.fallback;

import com.mayshop.gateway.error.ProblemResponses;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException;
import org.springframework.cloud.gateway.support.ServiceUnavailableException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * The gateway fallback: answers a failed call that went through a circuit breaker with 503 {@code
 * SERVICE_UNAVAILABLE} and counts it in {@code gateway.fallback{route, reason}}.
 *
 * <p>Reasons: {@code timeout} (route timeout or connect timeout), {@code circuit_open} (the breaker
 * rejected the call) and {@code upstream_error} (an upstream 5xx or a connect error). Any other
 * error is left to {@code ProblemErrorHandler}.
 */
@Component
@Order(-3)
public class FallbackHandler implements WebExceptionHandler {

  static final String METRIC = "gateway.fallback";
  static final long RETRY_AFTER_SECONDS = 2;

  private final MeterRegistry meters;

  public FallbackHandler(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
    String breaker = exchange.getAttribute(BreakerGatewayFilterFactory.BREAKER_ATTRIBUTE);
    if (breaker == null || exchange.getResponse().isCommitted()) {
      return Mono.error(error);
    }
    String reason = reason(error);
    if (reason == null) {
      return Mono.error(error);
    }
    meters.counter(METRIC, "route", breaker, "reason", reason).increment();
    return ProblemResponses.fallback(exchange, RETRY_AFTER_SECONDS, breaker, reason);
  }

  /** Returns the fallback reason of the error, or null when it is not a fallback case. */
  static String reason(Throwable error) {
    String found = null;
    for (Throwable t = error;
        t != null && found == null;
        t = t.getCause() == t ? null : t.getCause()) {
      found = reasonOf(t);
    }
    return found;
  }

  private static String reasonOf(Throwable t) {
    if (t instanceof CallNotPermittedException || t instanceof ServiceUnavailableException) {
      return "circuit_open";
    }
    if (t instanceof TimeoutException
        || t.getClass().getName().endsWith("ConnectTimeoutException")
        || t.getClass().getName().endsWith("ReadTimeoutException")) {
      return "timeout";
    }
    if (t instanceof CircuitBreakerStatusCodeException) {
      return "upstream_error";
    }
    if (t instanceof ResponseStatusException rse) {
      HttpStatusCode status = rse.getStatusCode();
      if (status.value() == 504) {
        return "timeout";
      }
      return status.value() == 502 || status.value() == 503 ? "upstream_error" : null;
    }
    return t instanceof IOException ? "upstream_error" : null;
  }
}
