package com.mayshop.core;

import java.util.List;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single {@link FlywayMigrationStrategy} of core. It migrates the auth module first (the
 * auto-configured {@code Flyway}, configured by {@code spring.flyway}), then every {@link
 * ModuleMigration} bean in {@code @Order}. The strategy only runs when Flyway is auto-configured,
 * so slices without a database are unaffected.
 */
@Configuration(proxyBeanMethods = false)
class ModuleMigrationConfiguration {

  @Bean
  FlywayMigrationStrategy moduleMigrationStrategy(List<ModuleMigration> migrations) {
    return auth -> {
      auth.migrate();
      var dataSource = auth.getConfiguration().getDataSource();
      migrations.forEach(migration -> migration.flyway(dataSource).migrate());
    };
  }
}
