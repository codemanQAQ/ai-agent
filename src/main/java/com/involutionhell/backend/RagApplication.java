package com.involutionhell.backend;

import com.involutionhell.backend.rag.infrastructure.nativeimage.RagRuntimeHints;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.modulith.Modulith;

@SpringBootApplication
@Modulith(
        systemName = "Involution Hell RAG Backend",
        sharedModules = "rag.shared"
)
@ImportRuntimeHints(RagRuntimeHints.class)
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }

}
