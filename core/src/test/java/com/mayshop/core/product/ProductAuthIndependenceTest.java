package com.mayshop.core.product;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The product module reads the token through Spring Security, never through the auth module. */
@AnalyzeClasses(packages = "com.mayshop.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ProductAuthIndependenceTest {

  @ArchTest
  static final ArchRule PRODUCT_DOES_NOT_DEPEND_ON_AUTH =
      noClasses()
          .that()
          .resideInAPackage("com.mayshop.core.product..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.mayshop.core.auth..")
          .because("the tier comes from the JwtAuthenticationToken claim, not from auth types")
          .allowEmptyShould(true);
}
