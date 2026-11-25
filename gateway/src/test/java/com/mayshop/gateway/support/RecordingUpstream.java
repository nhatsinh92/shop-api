package com.mayshop.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** A stub upstream that records "METHOD path" per request and can delay its answers. */
public final class RecordingUpstream implements AutoCloseable {

  private final HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final AtomicLong delayMillis = new AtomicLong();

  /** Starts the stub on a random local port. */
  public RecordingUpstream() {
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

  public void delay(long millis) {
    delayMillis.set(millis);
  }

  public List<String> requests() {
    return List.copyOf(requests);
  }

  public void reset() {
    requests.clear();
    delayMillis.set(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
    long delay = delayMillis.get();
    if (delay > 0) {
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    try {
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    } catch (IOException ignored) {
      // The gateway cut the connection after its timeout.
    } finally {
      exchange.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
