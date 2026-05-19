package com.involutionhell.backend;

import com.involutionhell.backend.rag.RagApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ApplicationModulesVerificationTests {

    @Test
    void verifiesModuleStructure() {
        ApplicationModules.of(RagApplication.class).verify();
    }
}
