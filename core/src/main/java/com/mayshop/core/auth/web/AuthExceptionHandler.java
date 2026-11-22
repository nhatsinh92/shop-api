package com.mayshop.core.auth.web;

import com.mayshop.core.auth.internal.InvalidCredentialsException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps auth failures to problem details with the codes of {@code errors.yaml}. */
@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthExceptionHandler {

  @ExceptionHandler(InvalidCredentialsException.class)
  ResponseEntity<Map<String, Object>> invalidCredentials() {
    return problem(HttpStatus.UNAUTHORIZED, "Invalid credentials", "AUTH_INVALID_CREDENTIALS");
  }

  @ExceptionHandler({InvalidRequestException.class, HttpMessageNotReadableException.class})
  ResponseEntity<Map<String, Object>> invalidRequest() {
    return problem(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_FAILED");
  }

  @ExceptionHandler(UnauthenticatedException.class)
  ResponseEntity<Map<String, Object>> unauthenticated() {
    return problem(HttpStatus.UNAUTHORIZED, "Unauthenticated", "AUTH_UNAUTHENTICATED");
  }

  private static ResponseEntity<Map<String, Object>> problem(
      HttpStatus status, String title, String code) {
    return ResponseEntity.status(status)
        .contentType(Problems.PROBLEM_JSON)
        .body(Problems.body(status, title, code));
  }
}
