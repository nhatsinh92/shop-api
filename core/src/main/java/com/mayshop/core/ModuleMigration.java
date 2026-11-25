package com.mayshop.core;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * One module's database migration: its own Flyway instance (location, schema, history table). A
 * module declares an {@link org.springframework.core.annotation.Order @Order}ed bean of this type;
 * {@link ModuleMigrationConfiguration} migrates the beans in order after the auth module.
 *
 * <p>This type lives in the application root package, which every module may use.
 */
public interface ModuleMigration {

  /** Returns the configured, not yet migrated, Flyway instance for this module. */
  Flyway flyway(DataSource dataSource);
}
