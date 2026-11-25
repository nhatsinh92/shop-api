package com.mayshop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.gateway.support.InMemoryRateLimiter;
import com.mayshop.gateway.support.RecordingUpstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Prometheus is exposed on the management port only. */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@AutoConfigureWebTestClient
class ManagementPortTest {

  private static final RecordingUpstream CORE = new RecordingUpstream();

  @TestConfiguration(proxyBeanMethods = false)
  static class Config {
    @Bean
    @Primary
    InMemoryRateLimiter inMemoryRateLimiter() {
      return new InMemoryRateLimiter();
    }
  }

  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) {
    registry.add("CORE_URL", CORE::url);
  }

  @AfterAll
  static void stop() {
    CORE.close();
  }

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

    assertThat(prometheus()).contains("http_server_requests_seconds_bucket");
  }

  @Test
  void requestHistogramsArePublishedForGatewayRequests() {
    appClient.get().uri("/api/products").exchange().expectStatus().isOk();

    assertThat(prometheus()).contains("spring_cloud_gateway_requests_seconds_bucket");
  }

  private String prometheus() {
    return management()
        .get()
        .uri("/actuator/prometheus")
        .exchange()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }
}
