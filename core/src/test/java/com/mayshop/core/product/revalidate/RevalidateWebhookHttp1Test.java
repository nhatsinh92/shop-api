package com.mayshop.core.product.revalidate;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.product.ProductUpdated;
import com.mayshop.core.product.ProductUpdated.Change;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The webhook must reach a Node/Next.js receiver, which closes a connection that asks for an {@code
 * Upgrade} (Java's default HTTP_2 client sends {@code Upgrade: h2c} on http:// URLs). Issue #95.
 */
class RevalidateWebhookHttp1Test {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final List<List<String>> requests = new CopyOnWriteArrayList<>();
  private ServerSocket server;
  private Thread acceptor;

  @BeforeEach
  void startReceiver() throws IOException {
    server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    acceptor = Thread.ofVirtual().start(this::acceptLoop);
  }

  @AfterEach
  void stopReceiver() throws IOException {
    server.close();
    acceptor.interrupt();
  }

  /**
   * Like `next start`: an Upgrade request is cut off without a response, anything else gets 204.
   */
  private void acceptLoop() {
    while (!server.isClosed()) {
      try (Socket socket = server.accept()) {
        BufferedReader in =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        List<String> headers = new CopyOnWriteArrayList<>();
        int contentLength = 0;
        for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
          headers.add(line);
          if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
            contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).strip());
          }
        }
        requests.add(headers);
        if (hasUpgrade(headers)) {
          continue; // closes the socket without a byte of response
        }
        in.skip(contentLength);
        OutputStream out = socket.getOutputStream();
        out.write(
            "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
      } catch (IOException e) {
        return;
      }
    }
  }

  private static boolean hasUpgrade(List<String> headers) {
    return headers.stream().anyMatch(h -> h.toLowerCase(Locale.ROOT).startsWith("upgrade:"));
  }

  private RevalidateWebhook webhook() {
    String url = "http://127.0.0.1:" + server.getLocalPort() + "/hooks/revalidate";
    return new RevalidateWebhook(
        url, "s3cret-value-for-tests", "2s", "10ms", JsonMapper.builder().build(), meters);
  }

  private static ProductUpdated priceChange() {
    UUID productId = UUID.randomUUID();
    return new ProductUpdated(
        UUID.randomUUID(),
        Instant.parse("2026-12-12T00:00:05Z"),
        productId,
        "SKU-MOUSE-SIL",
        "chuot-khong-day-im-lang",
        List.of(Change.PRICE),
        List.of("product-" + productId, "products"));
  }

  @Test
  void aPriceChangeReachesAReceiverThatRejectsUpgradesOnTheFirstAttempt() {
    webhook().on(priceChange());

    assertThat(meters.counter(RevalidateWebhook.FAILED_COUNTER).count()).isZero();
    assertThat(requests).hasSize(1);
    assertThat(requests.getFirst().getFirst()).startsWith("POST /hooks/revalidate HTTP/1.1");
    assertThat(hasUpgrade(requests.getFirst())).isFalse();
  }
}
