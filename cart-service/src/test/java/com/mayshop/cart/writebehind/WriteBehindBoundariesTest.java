package com.mayshop.cart.writebehind;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** No request path uses the datasource: only the write-behind consumer package depends on JDBC. */
@AnalyzeClasses(packages = "com.mayshop.cart", importOptions = ImportOption.DoNotIncludeTests.class)
class WriteBehindBoundariesTest {

  @ArchTest
  static final ArchRule ONLY_THE_CONSUMER_PACKAGE_USES_JDBC =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.mayshop.cart.writebehind.store..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "java.sql..",
              "javax.sql..",
              "org.springframework.jdbc..",
              "org.flywaydb..",
              "org.postgresql..",
              "com.zaxxer.hikari..")
          .because("requests are served from Redis; only the cart-writer consumer uses Postgres")
          .allowEmptyShould(true);
}
