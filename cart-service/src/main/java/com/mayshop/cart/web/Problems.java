package com.mayshop.cart.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/** RFC 9457 problem details with a stable {@code code}, as in {@code errors.yaml}. */
public final class Problems {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Problems() {}

  /** Builds the body of a problem response. */
  public static Map<String, Object> body(HttpStatus status, String title, String code) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", title);
    body.put("status", status.value());
    body.put("code", code);
    body.put("traceId", traceId());
    return body;
  }

  /** Writes a problem response, for filters that run before Spring MVC. */
  public static void write(
      HttpServletResponse response, HttpStatus status, String title, String code)
      throws IOException {
    response.setStatus(status.value());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(JSON.writeValueAsString(body(status, title, code)));
  }

  private static String traceId() {
    String fromMdc = MDC.get("traceId");
    if (fromMdc != null && !fromMdc.isBlank()) {
      return fromMdc;
    }
    return UUID.randomUUID().toString().replace("-", "");
  }
}
