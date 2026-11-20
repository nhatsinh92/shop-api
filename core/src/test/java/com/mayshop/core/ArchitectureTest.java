package com.mayshop.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;

/** Code rules that Spring Modulith does not cover. */
@AnalyzeClasses(packages = "com.mayshop.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule NO_FIELD_INJECTION =
      noFields()
          .should()
          .beAnnotatedWith(Autowired.class)
          .because("constructor injection keeps dependencies explicit")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule CORE_DOES_NOT_DEPEND_ON_OTHER_DEPLOYABLES =
      noClasses()
          .that()
          .resideInAPackage("com.mayshop.core..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.mayshop.cart..", "com.mayshop.gateway..")
          .because("core, cart-service and gateway are separate deployables")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule NO_JAVA_UTIL_LOGGING =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAPackage("java.util.logging..")
          .because("logging goes through SLF4J")
          .allowEmptyShould(true);
}
