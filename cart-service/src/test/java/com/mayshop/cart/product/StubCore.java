package com.mayshop.cart.product;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A stub of {@code core}'s lookup endpoint. */
final class StubCore implements AutoCloseable {

  private final HttpServer server;
  final AtomicInteger calls = new AtomicInteger();
  final List<String> requestBodies = new CopyOnWriteArrayList<>();
  final List<String> tokens = new CopyOnWriteArrayList<>();
  volatile long delayMillis;
  volatile int status = 200;
  volatile String responseBody = "{\"items\":[]}";

  StubCore() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/products/lookup",
        exchange -> {
          calls.incrementAndGet();
          tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Token")));
          requestBodies.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          if (delayMillis > 0) {
            try {
              Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          try {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
          } catch (IOException e) {
            // the client gave up (timeout test)
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  static String item(String sku, long price) {
    return "{\"sku\":\""
        + sku
        + "\",\"slug\":\"slug-"
        + sku.toLowerCase()
        + "\",\"name\":\"Name "
        + sku
        + "\",\"imageUrl\":\"https://cdn.example.com/"
        + sku
        + ".jpg\",\"price\":{\"amount\":"
        + price
        + ",\"currency\":\"VND\"},\"stockLevel\":\"IN_STOCK\"}";
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
