package com.mayshop.core.product.web;

import com.mayshop.core.product.update.InvalidUpdateException;
import com.mayshop.core.product.update.UnknownSkuException;
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

/** Problem details for the product endpoints; every error response is {@code no-store}. */
@RestControllerAdvice(
    assignableTypes = {
      ProductController.class,
      AvailabilityController.class,
      FlashSaleController.class,
      MemberPriceController.class,
      RecommendationController.class,
      InternalProductController.class
    })
class ProductExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ProductExceptionHandler.class);

  @ExceptionHandler(InvalidLimitException.class)
  ResponseEntity<Map<String, Object>> invalidLimit() {
    Map<String, Object> body =
        body(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_FAILED");
    body.put(
        "errors",
        List.of(
            Map.of(
                "field",
                "limit",
                "code",
                "VALIDATION_FAILED",
                "message",
                "must be between 1 and " + ProductController.MAX_LIMIT)));
    return response(HttpStatus.BAD_REQUEST, body);
  }

  @ExceptionHandler(InvalidRecommendationLimitException.class)
  ResponseEntity<Map<String, Object>> invalidRecommendationLimit() {
    Map<String, Object> body =
        body(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_FAILED");
    body.put(
        "errors",
        List.of(
            Map.of(
                "field",
                "limit",
                "code",
                "VALIDATION_FAILED",
                "message",
                "must be between 1 and " + RecommendationController.MAX_LIMIT)));
    return response(HttpStatus.BAD_REQUEST, body);
  }

  @ExceptionHandler(InvalidCursorException.class)
  ResponseEntity<Map<String, Object>> invalidCursor() {
    return response(
        HttpStatus.BAD_REQUEST,
        body(HttpStatus.BAD_REQUEST, "Invalid cursor", "PAGINATION_CURSOR_INVALID"));
  }

  @ExceptionHandler(ProductNotFoundException.class)
  ResponseEntity<Map<String, Object>> notFound() {
    return response(
        HttpStatus.NOT_FOUND, body(HttpStatus.NOT_FOUND, "Product not found", "PRODUCT_NOT_FOUND"));
  }

  @ExceptionHandler(UnknownSkuException.class)
  ResponseEntity<Map<String, Object>> unknownSku() {
    return notFound();
  }

  @ExceptionHandler(InvalidUpdateBodyException.class)
  ResponseEntity<Map<String, Object>> invalidUpdateBody(InvalidUpdateBodyException e) {
    Map<String, Object> body =
        body(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_FAILED");
    body.put(
        "errors",
        List.of(
            Map.of("field", e.field(), "code", "VALIDATION_FAILED", "message", e.getMessage())));
    return response(HttpStatus.BAD_REQUEST, body);
  }

  @ExceptionHandler(InvalidUpdateException.class)
  ResponseEntity<Map<String, Object>> invalidUpdate() {
    return response(
        HttpStatus.UNPROCESSABLE_CONTENT,
        body(HttpStatus.UNPROCESSABLE_CONTENT, "Product update invalid", "PRODUCT_UPDATE_INVALID"));
  }

  @ExceptionHandler(FlashSaleNoneException.class)
  ResponseEntity<Map<String, Object>> noFlashSale() {
    return response(
        HttpStatus.NOT_FOUND,
        body(HttpStatus.NOT_FOUND, "No flash sale is running", "FLASH_SALE_NONE"));
  }

  /** Anything unexpected: a problem response instead of a container error page. */
  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<Map<String, Object>> unexpected(RuntimeException e) {
    LOG.error("Product request failed", e);
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
