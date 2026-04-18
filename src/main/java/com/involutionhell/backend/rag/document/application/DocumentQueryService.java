package com.involutionhell.backend.rag.document.application;

import com.involutionhell.backend.rag.document.api.DocumentQueryFacade;
import com.involutionhell.backend.rag.document.api.RagDocumentView;
import com.involutionhell.backend.rag.document.persistence.RagDocumentRepository;
import com.involutionhell.backend.rag.document.persistence.RagDocumentRecord;
import org.springframework.stereotype.Service;

@Service
class DocumentQueryService implements DocumentQueryFacade {

    private final RagDocumentRepository documentRepository;

    DocumentQueryService(RagDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public RagDocumentView getDocument(Long documentId) {
        RagDocumentRecord record = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("RAG 文档不存在: " + documentId));
        return toView(record);
    }

    private RagDocumentView toView(RagDocumentRecord record) {
        return new RagDocumentView(
                record.id(),
                record.sourceType(),
                record.sourceUri(),
                record.externalRef(),
                record.title(),
                record.status(),
                record.chunkCount(),
                record.attemptCount(),
                record.lastError(),
                record.lastAttemptedAt(),
                record.indexedAt(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
