package com.mayshop.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Prometheus is served on the management port only; health answers on both ports. */
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
@Import(ManagementPortTest.Doubles.class)
class ManagementPortTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class Doubles {

    @Bean
    JdbcClient jdbcClient() {
      return Mockito.mock(JdbcClient.class);
    }
  }

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @LocalServerPort private int appPort;
  @LocalManagementPort private int managementPort;
  @Autowired private MeterRegistry meters;

  private int status(int port, String path) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
    return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  @Test
  void prometheusAnswers404OnTheAppPortAnd200OnTheManagementPort() throws Exception {
    assertThat(status(appPort, "/actuator/prometheus")).isEqualTo(404);
    assertThat(status(managementPort, "/actuator/prometheus")).isEqualTo(200);
  }

  @Test
  void healthAnswers200OnBothPorts() throws Exception {
    assertThat(status(appPort, "/actuator/health")).isEqualTo(200);
    assertThat(status(managementPort, "/actuator/health")).isEqualTo(200);
  }

  @Test
  void histogramsAreEnabledForRequestAndPaymentTimers() {
    Timer http = Timer.builder("http.server.requests").register(meters);
    Timer payment = Timer.builder("checkout.payment").register(meters);
    assertThat(http.takeSnapshot().histogramCounts()).isNotEmpty();
    assertThat(payment.takeSnapshot().histogramCounts()).isNotEmpty();
  }
}
