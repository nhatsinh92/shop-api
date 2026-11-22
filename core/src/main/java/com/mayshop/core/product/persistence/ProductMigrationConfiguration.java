package com.mayshop.core.product.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Migrates the {@code product} schema with its own Flyway instance: its own location, schema and
 * history table, separate from the auth module's (which {@code spring.flyway} still configures).
 *
 * <p>Boot's Flyway auto-configuration backs off once any {@link Flyway} bean exists, so the auth
 * instance stays the auto-configured bean (same location, schema and history table as before) and
 * each further module adds its own instance in a {@link FlywayMigrationStrategy} like this one. The
 * strategy only runs when Flyway is auto-configured, so slices without a database are unaffected.
 */
@Configuration(proxyBeanMethods = false)
class ProductMigrationConfiguration {

  static final String LOCATION = "classpath:db/migration/product";
  static final String SCHEMA = "product";
  static final String MODULITH_LOCATION = "classpath:db/migration/modulith";
  static final String MODULITH_SCHEMA = "modulith";

  @Bean
  FlywayMigrationStrategy productMigrationStrategy() {
    return auth -> {
      auth.migrate();
      productFlyway(auth).migrate();
      modulithFlyway(auth).migrate();
    };
  }

  /**
   * The event publication registry of Spring Modulith: schema {@code modulith}, own history table.
   */
  static Flyway modulithFlyway(Flyway auth) {
    return Flyway.configure()
        .dataSource(auth.getConfiguration().getDataSource())
        .locations(MODULITH_LOCATION)
        .schemas(MODULITH_SCHEMA)
        .defaultSchema(MODULITH_SCHEMA)
        .createSchemas(true)
        .load();
  }

  static Flyway productFlyway(Flyway auth) {
    return Flyway.configure()
        .dataSource(auth.getConfiguration().getDataSource())
        .locations(LOCATION)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .load();
  }
}
