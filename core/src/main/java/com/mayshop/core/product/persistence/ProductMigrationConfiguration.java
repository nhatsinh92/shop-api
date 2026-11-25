package com.mayshop.core.product.persistence;

import com.mayshop.core.ModuleMigration;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Declares the migrations of the {@code product} schema and of the {@code modulith} event
 * publication registry as {@link ModuleMigration} beans, each with its own location, schema and
 * history table. The strategy that runs them (after auth) is in the application root package.
 */
@Configuration(proxyBeanMethods = false)
class ProductMigrationConfiguration {

  static final String LOCATION = "classpath:db/migration/product";
  static final String SCHEMA = "product";
  static final String MODULITH_LOCATION = "classpath:db/migration/modulith";
  static final String MODULITH_SCHEMA = "modulith";

  @Bean
  @Order(10)
  ModuleMigration productMigration() {
    return dataSource -> flyway(dataSource, LOCATION, SCHEMA);
  }

  /** The event publication registry of Spring Modulith: schema {@code modulith}. */
  @Bean
  @Order(20)
  ModuleMigration modulithMigration() {
    return dataSource -> flyway(dataSource, MODULITH_LOCATION, MODULITH_SCHEMA);
  }

  private static Flyway flyway(javax.sql.DataSource dataSource, String location, String schema) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations(location)
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(true)
        .load();
  }
}
