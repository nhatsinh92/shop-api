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

/** Each module has its own Flyway instance; auth's history survives the split untouched. */
@Testcontainers(disabledWithoutDocker = true)
class ProductMigrationTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static final String AUTH_HISTORY =
      "select installed_rank, version, description, checksum, success"
          + " from auth.flyway_schema_history order by installed_rank";

  @Test
  void authKeepsItsHistoryAndProductGetsItsOwnWhenCoreStartsOnADatabaseMigratedByMain() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    // The configuration of main: only db/migration/auth, schema auth, history table in schema auth.
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/auth")
        .schemas("auth")
        .defaultSchema("auth")
        .createSchemas(true)
        .load()
        .migrate();
    var jdbc = JdbcClient.create(dataSource);
    List<Map<String, Object>> authBefore = jdbc.sql(AUTH_HISTORY).query().listOfRows();
    assertThat(authBefore).isNotEmpty();

    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(CoreApplication.class)
            .web(WebApplicationType.SERVLET)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword())) {

      assertThat(context.isRunning()).isTrue();
      assertThat(jdbc.sql(AUTH_HISTORY).query().listOfRows())
          .as("auth history: same rows, nothing re-applied or added")
          .isEqualTo(authBefore);
      List<String> productVersions =
          jdbc.sql(
                  "select version from product.flyway_schema_history"
                      + " where version is not null order by installed_rank")
              .query(String.class)
              .list();
      assertThat(productVersions).startsWith("1");
      assertThat(
              jdbc.sql(
                      "select count(*) from information_schema.tables"
                          + " where table_schema = 'product' and table_name = 'products'")
                  .query(Long.class)
                  .single())
          .isEqualTo(1L);
      assertThat(
              jdbc.sql(
                      "select count(*) from auth.flyway_schema_history"
                          + " where description ilike '%product%'")
                  .query(Long.class)
                  .single())
          .as("product migrations are not recorded in auth's history")
          .isZero();
    }
  }
}
