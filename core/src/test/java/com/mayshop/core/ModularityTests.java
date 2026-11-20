package com.mayshop.core;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Verifies the application module structure of core. Runs in CI on every PR. */
class ModularityTests {

  private final ApplicationModules modules = ApplicationModules.of(CoreApplication.class);

  @Test
  void verifiesModuleStructure() {
    modules.verify();
  }

  @Test
  void writesModuleDocumentation() {
    new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
  }
}
