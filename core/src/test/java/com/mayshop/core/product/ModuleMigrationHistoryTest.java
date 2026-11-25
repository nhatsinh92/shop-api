package com.mayshop.core.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.mayshop.core.CoreApplication;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The per-module migration beans keep every history table intact on a database migrated by main.
 */
@Testcontainers(disabledWithoutDocker = true)
class ModuleMigrationHistoryTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static final List<String> SCHEMAS = List.of("auth", "product", "modulith");

  @Test
  void noMigrationIsReappliedAndEveryHistoryKeepsItsRowsWhenCoreStartsOnADatabaseMigratedByMain() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    // The configuration of main: auth, then product, then modulith, each with its own history.
    for (String schema : SCHEMAS) {
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration/" + schema)
          .schemas(schema)
          .defaultSchema(schema)
          .createSchemas(true)
          .load()
          .migrate();
    }
    var jdbc = JdbcClient.create(dataSource);
    Map<String, List<Map<String, Object>>> before = histories(jdbc);
    SCHEMAS.forEach(schema -> assertThat(before.get(schema)).as(schema).isNotEmpty());

    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(CoreApplication.class)
            .web(WebApplicationType.SERVLET)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword())) {

      assertThat(context.isRunning()).isTrue();
      assertThat(histories(jdbc))
          .as("same rows in every history table: nothing re-applied, nothing added")
          .isEqualTo(before);
    }
  }

  private static Map<String, List<Map<String, Object>>> histories(JdbcClient jdbc) {
    var result = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
    for (String schema : SCHEMAS) {
      result.put(
          schema,
          jdbc.sql(
                  "select installed_rank, version, description, checksum, success from "
                      + schema
                      + ".flyway_schema_history order by installed_rank")
              .query()
              .listOfRows());
    }
    return result;
  }
}
