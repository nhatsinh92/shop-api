package com.mayshop.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** The one migration strategy runs auth first, then the module migrations in order. */
class ModuleMigrationConfigurationTest {

  private static final List<String> CALLS = new ArrayList<>();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(ModuleMigrationConfiguration.class, FakeModules.class);

  @Test
  void migratesAuthFirstThenEveryModuleMigrationInOrder() {
    CALLS.clear();
    runner.run(context -> {
      var strategy = context.getBean(FlywayMigrationStrategy.class);
      strategy.migrate(recordingFlyway("auth"));
      assertThat(CALLS).containsExactly("auth", "product", "modulith", "late");
    });
  }

  @Test
  void declaresExactlyOneStrategy() {
    runner.run(context -> assertThat(context).hasSingleBean(FlywayMigrationStrategy.class));
  }

  private static Flyway recordingFlyway(String name) {
    var flyway = mock(Flyway.class);
    var configuration = mock(FluentConfiguration.class);
    when(configuration.getDataSource()).thenReturn(mock(DataSource.class));
    when(flyway.getConfiguration()).thenReturn(configuration);
    when(flyway.migrate())
        .thenAnswer(invocation -> {
          CALLS.add(name);
          return null;
        });
    return flyway;
  }

  @Configuration(proxyBeanMethods = false)
  static class FakeModules {

    // Declared out of order on purpose: @Order decides, not the declaration order.
    @Bean
    @Order(30)
    ModuleMigration late() {
      return dataSource -> recordingFlyway("late");
    }

    @Bean
    @Order(20)
    ModuleMigration modulith() {
      return dataSource -> recordingFlyway("modulith");
    }

    @Bean
    @Order(10)
    ModuleMigration product() {
      return dataSource -> recordingFlyway("product");
    }
  }
}
