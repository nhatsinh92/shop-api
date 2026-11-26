package com.mayshop.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

/** Prometheus is exposed on the management port only; health and info stay on both ports. */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
class ManagementPortTest {

  @MockitoBean private JdbcClient jdbc;
  @MockitoBean private PlatformTransactionManager transactions;

  @LocalServerPort private int appPort;
  @LocalManagementPort private int managementPort;
  @Autowired private MeterRegistry meters;

  private HttpStatusCode get(int port, String path) {
    return RestClient.create("http://localhost:" + port)
        .get()
        .uri(path)
        .exchange((request, response) -> response.getStatusCode());
  }

  @Test
  void prometheusAnswers200OnTheManagementPort() {
    assertThat(get(managementPort, "/actuator/prometheus").value()).isEqualTo(200);
  }

  @Test
  void prometheusIsNotFoundOnTheAppPort() {
    assertThat(get(appPort, "/actuator/prometheus").value()).isEqualTo(404);
  }

  @Test
  void healthAnswers200OnBothPorts() {
    assertThat(get(appPort, "/actuator/health").value()).isEqualTo(200);
    assertThat(get(managementPort, "/actuator/health").value()).isEqualTo(200);
  }

  @Test
  void healthProbesAndInfoStayOnTheAppPort() {
    assertThat(get(appPort, "/actuator/health/liveness").value()).isEqualTo(200);
    assertThat(get(appPort, "/actuator/info").value()).isEqualTo(200);
  }

  @Test
  void percentilesHistogramIsEnabledForRequestsAndPayments() {
    Timer.builder("checkout.payment").register(meters).record(java.time.Duration.ofMillis(5));
    get(appPort, "/actuator/info");

    var prometheus =
        RestClient.create("http://localhost:" + managementPort)
            .get()
            .uri("/actuator/prometheus")
            .retrieve()
            .body(String.class);
    assertThat(prometheus)
        .contains("http_server_requests_seconds_bucket")
        .contains("checkout_payment_seconds_bucket");
  }
}
