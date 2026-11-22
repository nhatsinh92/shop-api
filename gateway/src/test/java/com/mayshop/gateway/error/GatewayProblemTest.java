package com.mayshop.gateway.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.ProblemSchema;
import com.mayshop.gateway.support.StubServer;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayProblemTest {

  private static final StubServer SLOW_CORE = new StubServer();

  @Autowired private WebTestClient client;

  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) throws IOException {
    SLOW_CORE.delay(2000);
    registry.add("CORE_URL", SLOW_CORE::url);
    registry.add("CART_SERVICE_URL", () -> "http://127.0.0.1:" + closedPort());
    registry.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> "300ms");
  }

  @AfterAll
  static void stop() {
    SLOW_CORE.close();
  }

  @Test
  void noRouteAnswersNotFoundAsAProblem() {
    assertProblem("/nothing-here", 404, "NOT_FOUND");
  }

  @Test
  void anUnreachableUpstreamAnswersServiceUnavailableAsAProblem() {
    assertProblem("/api/cart", 503, "SERVICE_UNAVAILABLE");
  }

  @Test
  void anUpstreamThatTimesOutAnswersServiceUnavailableAsAProblem() {
    assertProblem("/api/products", 503, "SERVICE_UNAVAILABLE");
  }

  @Test
  void anUnexpectedFailureAnswersInternalErrorAsAProblem() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

    new ProblemErrorHandler().handle(exchange, new IllegalStateException("boom")).block();

    String body = exchange.getResponse().getBodyAsString().block();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(exchange.getResponse().getHeaders().getContentType().toString())
        .isEqualTo("application/problem+json");
    assertThat(ProblemSchema.violations(body)).isEmpty();
    assertThat(body).contains("\"code\":\"INTERNAL_ERROR\"").contains("traceId");
    assertThat(body).doesNotContain("boom");
  }

  private void assertProblem(String path, int status, String code) {
    String body =
        client
            .get()
            .uri(path)
            .exchange()
            .expectStatus()
            .isEqualTo(status)
            .expectHeader()
            .contentType("application/problem+json")
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertThat(ProblemSchema.violations(body)).isEmpty();
    assertThat(body).contains("\"code\":\"" + code + "\"").contains("\"traceId\"");
    assertThat(body.getBytes(StandardCharsets.UTF_8)).isNotEmpty();
  }

  private static int closedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
