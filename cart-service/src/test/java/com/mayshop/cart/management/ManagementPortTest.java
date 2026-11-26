package com.mayshop.cart.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Prometheus lives on the management port only; health and info answer on both ports. */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      // No Redis in this test: the point is where the endpoints are served, not their content.
      "management.health.redis.enabled=false",
      "spring.kafka.listener.auto-startup=false"
    })
class ManagementPortTest {

  @LocalServerPort private int appPort;
  @LocalManagementPort private int managementPort;

  private final HttpClient http = HttpClient.newHttpClient();

  private int get(int port, String path) throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
            HttpResponse.BodyHandlers.ofString())
        .statusCode();
  }

  private String body(int port, String path) throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
            HttpResponse.BodyHandlers.ofString())
        .body();
  }

  @Test
  void prometheusAnswers200OnTheManagementPort() throws Exception {
    assertThat(get(managementPort, "/actuator/prometheus")).isEqualTo(200);
  }

  @Test
  void prometheusIsNotFoundOnTheAppPort() throws Exception {
    assertThat(get(appPort, "/actuator/prometheus")).isEqualTo(404);
  }

  @Test
  void healthAndInfoAnswer200OnBothPorts() throws Exception {
    for (int port : new int[] {appPort, managementPort}) {
      assertThat(get(port, "/actuator/health")).isEqualTo(200);
      assertThat(get(port, "/actuator/info")).isEqualTo(200);
    }
  }

  @Test
  void healthProbesAnswerOnTheAppPort() throws Exception {
    assertThat(get(appPort, "/actuator/health/liveness")).isEqualTo(200);
    assertThat(get(appPort, "/actuator/health/readiness")).isEqualTo(200);
    assertThat(get(appPort, "/actuator/health/nope")).isEqualTo(404);
  }

  @Test
  void productLookupPublishesAHistogramAndTheCacheCounters() throws Exception {
    String metrics = body(managementPort, "/actuator/prometheus");

    assertThat(metrics).contains("cart_product_lookup_seconds_bucket");
    assertThat(metrics).contains("cart_product_cache_total{").contains("result=\"stale\"");
    assertThat(metrics).contains("resilience4j_circuitbreaker_state");
  }
}
