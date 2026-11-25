package com.mayshop.core.checkout.persistence;

import com.mayshop.core.ModuleMigration;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Declares the migration of the {@code checkout} schema as a {@link ModuleMigration} bean, ordered
 * after the product and modulith migrations. The strategy that runs it is in the application root
 * package.
 */
@Configuration(proxyBeanMethods = false)
class CheckoutMigrationConfiguration {

  static final String LOCATION = "classpath:db/migration/checkout";
  static final String SCHEMA = "checkout";

  @Bean
  @Order(30)
  ModuleMigration checkoutMigration() {
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
