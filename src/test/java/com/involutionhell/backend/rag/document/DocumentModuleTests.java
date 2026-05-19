package com.involutionhell.backend.rag.document;

import com.involutionhell.backend.rag.document.api.DocumentCommandFacade;
import com.involutionhell.backend.rag.document.api.DocumentIndexCleanupRequestedEvent;
import com.involutionhell.backend.rag.document.api.DocumentIndexRequestedEvent;
import com.involutionhell.backend.rag.document.api.DocumentQueryFacade;
import com.involutionhell.backend.rag.document.api.RagDocumentCreateRequest;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(
        module = "document",
        extraIncludes = "rag.infrastructure",
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-modulith.sql"
})
class DocumentModuleTests {

    @Autowired
    private DocumentCommandFacade documentCommandFacade;

    @Autowired
    private DocumentQueryFacade documentQueryFacade;

    @Autowired
    private DocumentIndexingSpi documentIndexingSpi;

    @Test
    void exposesDocumentFacadesAndSpi() {
        assertThat(documentCommandFacade).isNotNull();
        assertThat(documentQueryFacade).isNotNull();
        assertThat(documentIndexingSpi).isNotNull();
    }

    @Test
    void createDocumentPublishesIndexRequestedEvent(Scenario scenario) {
        RagDocumentCreateRequest request = new RagDocumentCreateRequest(
                "markdown",
                "docs/test-create.md",
                "doc-create-1",
                "Create Event Test",
                "# Title\n\nThis is a document used to verify index request event publishing.",
                Map.of("authorEmail", "tester@example.com")
        );

        scenario.stimulate(() -> documentCommandFacade.createDocument(request))
                .andWaitForEventOfType(DocumentIndexRequestedEvent.class)
                .matchingMappedValue(DocumentIndexRequestedEvent::triggeredBy, "document-command-service")
                .toArriveAndVerify((event, documentView) -> {
                    assertThat(event.documentId()).isEqualTo(documentView.id());
                    assertThat(event.contentSha256()).isNotBlank();
                    assertThat(documentIndexingSpi.findById(documentView.id()))
                            .map(DocumentIndexingView::status)
                            .contains("PENDING");
                });
    }

    @Test
    void deleteDocumentPublishesCleanupEvent(Scenario scenario) {
        Long documentId = documentCommandFacade.createDocument(new RagDocumentCreateRequest(
                "markdown",
                "docs/test-delete.md",
                "doc-delete-1",
                "Delete Event Test",
                "# Delete\n\nThis document is used to verify cleanup event publishing.",
                Map.of()
        )).id();

        scenario.stimulate(() -> {
                    documentCommandFacade.deleteDocument(documentId);
                    return documentId;
                })
                .andWaitForEventOfType(DocumentIndexCleanupRequestedEvent.class)
                .matchingMappedValue(DocumentIndexCleanupRequestedEvent::documentId, documentId)
                .toArriveAndVerify((event, returnedDocumentId) -> {
                    assertThat(event.documentId()).isEqualTo(returnedDocumentId);
                    assertThat(documentIndexingSpi.findById(documentId))
                            .map(DocumentIndexingView::status)
                            .contains("DELETING");
                });
    }
}
