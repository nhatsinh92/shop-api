package com.mayshop.core.checkout.web;

import com.mayshop.core.checkout.OutOfStockException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Problem details (RFC 9457) for the checkout endpoints; every error response is no-store. */
@RestControllerAdvice(assignableTypes = {ReservationController.class, OptionsController.class})
class CheckoutExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CheckoutExceptionHandler.class);

  @ExceptionHandler(InvalidReservationRequestException.class)
  ResponseEntity<Map<String, Object>> invalid(InvalidReservationRequestException e) {
    Map<String, Object> body =
        body(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_FAILED");
    body.put(
        "errors",
        e.violations().stream()
            .map(v ->
                Map.of("field", v.field(), "code", "VALIDATION_FAILED", "message", v.message()))
            .toList());
    return response(HttpStatus.BAD_REQUEST, body);
  }

  @ExceptionHandler(OutOfStockException.class)
  ResponseEntity<Map<String, Object>> outOfStock(OutOfStockException e) {
    Map<String, Object> body = body(HttpStatus.CONFLICT, "Out of stock", "CHECKOUT_OUT_OF_STOCK");
    List<Map<String, Object>> unavailable =
        e.unavailable().stream()
            .map(line -> {
              Map<String, Object> entry = new LinkedHashMap<>();
              entry.put("sku", line.sku());
              entry.put("requested", line.requested());
              entry.put("available", line.available());
              return entry;
            })
            .toList();
    body.put("unavailable", unavailable);
    return response(HttpStatus.CONFLICT, body);
  }

  @ExceptionHandler(ReservationNotFoundException.class)
  ResponseEntity<Map<String, Object>> notFound() {
    return response(
        HttpStatus.NOT_FOUND,
        body(HttpStatus.NOT_FOUND, "Reservation not found", "CHECKOUT_RESERVATION_NOT_FOUND"));
  }

  /** Anything unexpected: a problem response instead of a container error page. */
  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<Map<String, Object>> unexpected(RuntimeException e) {
    LOG.error("Checkout request failed", e);
    return response(
        HttpStatus.INTERNAL_SERVER_ERROR,
        body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "INTERNAL_ERROR"));
  }

  private static ResponseEntity<Map<String, Object>> response(
      HttpStatus status, Map<String, Object> body) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  private static Map<String, Object> body(HttpStatus status, String title, String code) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", title);
    body.put("status", status.value());
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
