package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.CoreApplication;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * An event whose broker was unreachable stays in the registry and is sent after a restart: the
 * first run points at a broker that does not exist, the second at Redpanda.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProductUpdatedResubmissionTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Container
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.3.7");

  private static ConfigurableApplicationContext start(String kafka) {
    return new SpringApplicationBuilder(CoreApplication.class)
        .web(WebApplicationType.SERVLET)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.kafka.bootstrap-servers=" + kafka,
            "--spring.kafka.producer.properties.max.block.ms=1000",
            "--spring.kafka.producer.properties.delivery.timeout.ms=2000",
            "--spring.kafka.producer.properties.request.timeout.ms=1000",
            "--spring.modulith.events.republish-outstanding-events-on-restart=true");
  }

  @Test
  void anIncompletePublicationIsResubmittedOnRestart() throws Exception {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var jdbc = JdbcClient.create(dataSource);
    String sku = "SKU-RESUBMIT";

    try (ConfigurableApplicationContext first = start("localhost:1")) {
      var event =
          new ProductUpdated(
              java.util.UUID.randomUUID(),
              java.time.Instant.now(),
              java.util.UUID.randomUUID(),
              sku,
              "slug",
              List.of(ProductUpdated.Change.PRICE),
              List.of("products"));
      first.getBean(TransactionTemplate.class).executeWithoutResult(s -> first.publishEvent(event));
      Thread.sleep(3000);
      assertThat(
              jdbc.sql(
                      "select count(*) from modulith.event_publication"
                          + " where completion_date is null and serialized_event like ?")
                  .param("%" + sku + "%")
                  .query(Integer.class)
                  .single())
          .as("the publication stays incomplete while the broker is unreachable")
          .isEqualTo(1);
    }

    try (ConfigurableApplicationContext second = start(REDPANDA.getBootstrapServers())) {
      assertThat(second.isRunning()).isTrue();
      var records =
          ProductEventSupport.recordsWithKey(
              REDPANDA.getBootstrapServers(), "product.updated", sku, Duration.ofSeconds(10));
      assertThat(records).hasSize(1);
      assertThat(ProductEventSupport.violations(records.get(0).value())).isEmpty();
      assertThat(
              jdbc.sql(
                      "select count(*) from modulith.event_publication"
                          + " where completion_date is null and serialized_event like ?")
                  .param("%" + sku + "%")
                  .query(Integer.class)
                  .single())
          .as("completed after the restart")
          .isZero();
    }
  }
}
