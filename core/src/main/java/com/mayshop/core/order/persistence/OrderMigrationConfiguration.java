package com.mayshop.core.order.persistence;

import com.mayshop.core.ModuleMigration;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Declares the migration of the {@code orders} schema ({@code order} is an SQL keyword) as a {@link
 * ModuleMigration} bean with its own location, schema and history table. It runs after the product,
 * modulith and checkout migrations.
 */
@Configuration(proxyBeanMethods = false)
class OrderMigrationConfiguration {

  static final String LOCATION = "classpath:db/migration/orders";
  static final String SCHEMA = "orders";

  @Bean
  @Order(40)
  ModuleMigration ordersMigration() {
    return dataSource ->
        Flyway.configure()
            .dataSource(dataSource)
            .locations(LOCATION)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .createSchemas(true)
            .load();
  }
}
