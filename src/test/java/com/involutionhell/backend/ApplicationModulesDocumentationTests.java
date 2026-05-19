package com.involutionhell.backend;

import com.involutionhell.backend.rag.RagApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ApplicationModulesDocumentationTests {

    @Test
    void writesModuleDocumentation() {
        ApplicationModules modules = ApplicationModules.of(RagApplication.class);
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases()
                .writeAggregatingDocument();
    }
}
