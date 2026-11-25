package com.mayshop.core.order.web;

import com.mayshop.core.order.internal.OrderProblemException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Problem details (RFC 9457) for the order endpoints; every error response is no-store. */
@RestControllerAdvice(assignableTypes = OrderController.class)
class OrderExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(OrderExceptionHandler.class);

  @ExceptionHandler(IdempotencyKeyMissingException.class)
  ResponseEntity<Map<String, Object>> keyMissing() {
    return response(400, body(400, "Idempotency key missing", "IDEMPOTENCY_KEY_MISSING"));
  }

  @ExceptionHandler(InvalidOrderRequestException.class)
  ResponseEntity<Map<String, Object>> invalid(InvalidOrderRequestException e) {
    Map<String, Object> body = body(400, "Validation failed", "VALIDATION_FAILED");
    body.put(
        "errors",
        e.violations().stream()
            .map(v ->
                Map.of("field", v.field(), "code", "VALIDATION_FAILED", "message", v.message()))
            .toList());
    return response(400, body);
  }

  @ExceptionHandler(OrderProblemException.class)
  ResponseEntity<Map<String, Object>> problem(OrderProblemException e) {
    return response(e.status(), body(e.status(), e.title(), e.code()));
  }

  /** Anything unexpected: a problem response instead of a container error page. */
  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<Map<String, Object>> unexpected(RuntimeException e) {
    LOG.error("Order request failed", e);
    return response(500, body(500, "Internal error", "INTERNAL_ERROR"));
  }

  private static ResponseEntity<Map<String, Object>> response(
      int status, Map<String, Object> body) {
    return ResponseEntity.status(HttpStatusCode.valueOf(status))
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  private static Map<String, Object> body(int status, String title, String code) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", title);
    body.put("status", status);
    body.put("code", code);
    String traceId = MDC.get("traceId");
    body.put(
        "traceId",
        traceId != null && !traceId.isBlank()
            ? traceId
            : UUID.randomUUID().toString().replace("-", ""));
    return body;
  }
}
