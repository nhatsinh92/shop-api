package com.mayshop.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.StubServer;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Pins the behaviour when Redis is down: fail open, with a WARN, never a 403 or a 429. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class RedisUnavailableTest {

  private static final StubServer CORE = new StubServer();

  @Autowired private WebTestClient client;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    registry.add("CORE_URL", CORE::url);
    registry.add("REDIS_PORT", () -> closedPort);
  }

  @AfterAll
  static void stop() {
    CORE.close();
  }

  @Test
  void requestsAreAllowedAndWarnedAboutWhenRedisIsUnreachable(CapturedOutput output) {
    for (int i = 0; i < 8; i++) {
      client
          .post()
          .uri("/api/auth/login")
          .header("Content-Type", "application/json")
          .bodyValue("{\"email\":\"a@example.com\",\"password\":\"x\"}")
          .exchange()
          .expectStatus()
          .isUnauthorized();
    }
    client.get().uri("/api/products").exchange().expectStatus().isUnauthorized();
    assertThat(output.getOut()).contains("Rate limit skipped, Redis unavailable");
  }

  @Test
  void aRequestWithAnEmptyKeyIsNeverAnswered403() {
    client
        .post()
        .uri("/api/auth/login")
        .header("Content-Type", "application/json")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }
}
