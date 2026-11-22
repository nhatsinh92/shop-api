package com.mayshop.gateway.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stub upstream: serves a JWKS, counts its fetches, and plays a protected service that answers
 * 401 {@code AUTH_UNAUTHENTICATED} without a bearer token and otherwise echoes what it received.
 */
public final class StubServer implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final AtomicInteger jwksFetches = new AtomicInteger();
  private final Map<String, Map<String, List<String>>> lastHeaders = new ConcurrentHashMap<>();
  private volatile JWKSet jwks = new JWKSet();
  private volatile int jwksStatus = 200;
  private volatile long delayMillis;

  /** Starts the stub on a random local port. */
  public StubServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void serveKeys(JWKSet keys) {
    this.jwks = keys;
  }

  public void failJwks(int status) {
    this.jwksStatus = status;
  }

  public void delay(long millis) {
    this.delayMillis = millis;
  }

  public int jwksFetches() {
    return jwksFetches.get();
  }

  public void resetCounters() {
    jwksFetches.set(0);
    lastHeaders.clear();
    jwksStatus = 200;
    delayMillis = 0;
  }

  /** Headers the stub received for the last request to the path (lower-case names). */
  public Map<String, List<String>> lastHeaders(String path) {
    return lastHeaders.getOrDefault(path, Map.of());
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (delayMillis > 0) {
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if ("/.well-known/jwks.json".equals(path)) {
      jwksFetches.incrementAndGet();
      reply(exchange, jwksStatus, "application/json", jwks.toString());
      return;
    }
    Map<String, List<String>> headers =
        exchange.getRequestHeaders().entrySet().stream()
            .collect(
                Collectors.toMap(
                    e -> e.getKey().toLowerCase(java.util.Locale.ROOT), Map.Entry::getValue));
    lastHeaders.put(path, headers);
    if (!headers.containsKey("authorization")) {
      reply(
          exchange,
          401,
          "application/problem+json",
          JSON.writeValueAsString(
              Map.of(
                  "type", "about:blank",
                  "title", "Unauthenticated",
                  "status", 401,
                  "code", "AUTH_UNAUTHENTICATED")));
      return;
    }
    reply(exchange, 200, "application/json", JSON.writeValueAsString(Map.of("ok", true)));
  }

  private static void reply(HttpExchange exchange, int status, String type, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
