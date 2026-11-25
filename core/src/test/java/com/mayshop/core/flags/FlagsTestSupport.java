package com.mayshop.core.flags;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Shared settings of the flags tests. */
public final class FlagsTestSupport {

  /** Starts the application without a database (flags need only Redis). */
  public static final String DB_FREE_EXCLUDES =
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
          + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

  private FlagsTestSupport() {}

  /** Stands in for the database beans the other modules need at startup. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class NoDatabase {

    @Bean
    JdbcClient jdbcClient() {
      return Mockito.mock(JdbcClient.class);
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      return Mockito.mock(PlatformTransactionManager.class);
    }
  }
}
