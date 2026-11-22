package com.mayshop.core.product.revalidate;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;

/** A local HTTP server standing in for shop-web; records every request it receives. */
final class StubServer implements AutoCloseable {

  record Call(String signature, String contentType, byte[] body) {}

  private final HttpServer server;
  private final List<Call> calls = new CopyOnWriteArrayList<>();

  /** {@code behaviour} maps the 1-based call number to a status; a negative value hangs 3 s. */
  StubServer(IntFunction<Integer> behaviour) throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/hooks/revalidate",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          calls.add(
              new Call(
                  exchange.getRequestHeaders().getFirst("X-Shop-Signature"),
                  exchange.getRequestHeaders().getFirst("Content-Type"),
                  body));
          int status = behaviour.apply(calls.size());
          if (status < 0) {
            try {
              Thread.sleep(3000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            status = 204;
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/hooks/revalidate";
  }

  List<Call> calls() {
    return calls;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
