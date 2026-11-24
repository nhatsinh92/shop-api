package com.mayshop.cart.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps failures of the cart endpoints to {@code application/problem+json} responses. */
@RestControllerAdvice
class CartExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CartExceptionHandler.class);

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentNotValidException.class,
    HandlerMethodValidationException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    ConversionException.class
  })
  ResponseEntity<Map<String, Object>> validation(Exception e) {
    LOG.debug("Invalid request: {}", e.toString());
    return problem(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_FAILED");
  }

  @ExceptionHandler({
    RedisConnectionFailureException.class,
    RedisSystemException.class,
    DataAccessException.class
  })
  ResponseEntity<Map<String, Object>> redisUnavailable(Exception e) {
    LOG.warn("Redis is unavailable: {}", e.toString());
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", "SERVICE_UNAVAILABLE");
  }

  private static ResponseEntity<Map<String, Object>> problem(
      HttpStatus status, String title, String code) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(Problems.body(status, title, code));
  }
}
