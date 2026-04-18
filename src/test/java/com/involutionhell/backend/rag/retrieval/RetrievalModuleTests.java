package com.involutionhell.backend.rag.retrieval;

import com.involutionhell.backend.rag.retrieval.api.RagAskFacade;
import com.involutionhell.backend.rag.retrieval.api.RagAskRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(
        module = "rag.retrieval",
        mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES,
        extraIncludes = "rag.infrastructure",
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-modulith.sql"
})
class RetrievalModuleTests {

    @Autowired
    private RagAskFacade ragAskFacade;

    @Test
    void exposesRetrievalFacade() {
        assertThat(ragAskFacade).isNotNull();
    }

    @Test
    void askFallsBackGracefullyWhenNoContextIsFound() {
        var response = ragAskFacade.ask(new RagAskRequest(
                "当前知识库里有关于 Modulith 的内容吗？",
                3,
                null,
                List.of(),
                null,
                List.of()
        ));

        assertThat(response.generatedByModel()).isFalse();
        assertThat(response.contexts()).isEmpty();
        assertThat(response.answer()).contains("我没有检索到可用的知识片段");
    }
}
