package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.document.api.DocumentIndexCleanupRequestedEvent;
import com.involutionhell.backend.rag.document.api.DocumentIndexRequestedEvent;
import com.involutionhell.backend.rag.indexing.api.IndexingCommandFacade;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class IndexingDocumentEventListener {

    private final IndexingCommandFacade indexingCommandFacade;

    IndexingDocumentEventListener(IndexingCommandFacade indexingCommandFacade) {
        this.indexingCommandFacade = indexingCommandFacade;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onDocumentIndexRequested(DocumentIndexRequestedEvent event) {
        indexingCommandFacade.requestIndexing(
                event.documentId(),
                event.contentSha256(),
                event.triggeredBy()
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onDocumentIndexCleanupRequested(DocumentIndexCleanupRequestedEvent event) {
        indexingCommandFacade.cleanupPendingIndexing(event.documentId());
    }
}
