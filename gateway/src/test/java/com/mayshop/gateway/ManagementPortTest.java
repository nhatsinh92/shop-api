package com.mayshop.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Prometheus is exposed on the management port only. */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@AutoConfigureWebTestClient
class ManagementPortTest {

  @Autowired private WebTestClient appClient;
  @LocalManagementPort private int managementPort;

  private WebTestClient management() {
    return WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build();
  }

  @Test
  void prometheusAnswers200OnTheManagementPort() {
    management().get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
  }

  @Test
  void prometheusIsNotFoundOnTheAppPort() {
    appClient.get().uri("/actuator/prometheus").exchange().expectStatus().isNotFound();
  }

  @Test
  void healthAndInfoStayOnTheAppPort() {
    appClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
    appClient.get().uri("/actuator/info").exchange().expectStatus().isOk();
  }

  @Test
  void requestHistogramsArePublishedForHttpServerRequests() {
    appClient.get().uri("/actuator/health").exchange().expectStatus().isOk();

    String body =
        management()
            .get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    org.assertj.core.api.Assertions.assertThat(body)
        .contains("http_server_requests_seconds_bucket");
  }
}
