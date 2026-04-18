package com.involutionhell.backend;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ApplicationModulesVerificationTests {

    @Test
    void verifiesModuleStructure() {
        ApplicationModules.of(RagApplication.class).verify();
    }
}
