package com.bytedance.ai;

import com.bytedance.ai.RagApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ApplicationModulesVerificationTests {

    @Test
    void verifiesModuleStructure() {
        ApplicationModules.of(RagApplication.class).verify();
    }
}
