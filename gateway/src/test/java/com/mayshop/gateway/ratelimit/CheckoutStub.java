package com.mayshop.gateway.ratelimit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A stub {@code core} for the checkout routes: serves the JWKS, answers every other request with
 * 200 and {@code Idempotent-Replayed: true}, records the headers it received and counts the
 * requests that reached it.
 */
final class CheckoutStub implements AutoCloseable {

  private final HttpServer server;
  private final AtomicInteger requests = new AtomicInteger();
  private final Map<String, List<String>> lastHeaders = new ConcurrentHashMap<>();
  private volatile com.nimbusds.jose.jwk.JWKSet jwks = new com.nimbusds.jose.jwk.JWKSet();

  CheckoutStub() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void serveKeys(com.nimbusds.jose.jwk.JWKSet keys) {
    this.jwks = keys;
  }

  int requests() {
    return requests.get();
  }

  void reset() {
    requests.set(0);
    lastHeaders.clear();
  }

  /** Headers of the last request that reached the stub, with lower-case names. */
  Map<String, List<String>> lastHeaders() {
    return lastHeaders;
  }

  private void handle(HttpExchange exchange) throws IOException {
    if ("/.well-known/jwks.json".equals(exchange.getRequestURI().getPath())) {
      reply(exchange, jwks.toString());
      return;
    }
    requests.incrementAndGet();
    lastHeaders.clear();
    lastHeaders.putAll(
        exchange.getRequestHeaders().entrySet().stream()
            .collect(
                Collectors.toMap(e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue)));
    exchange.getResponseHeaders().set("Idempotent-Replayed", "true");
    reply(exchange, "{\"ok\":true}");
  }

  private static void reply(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
