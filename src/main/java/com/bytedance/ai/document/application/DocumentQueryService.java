package com.bytedance.ai.document.application;

import com.bytedance.ai.document.api.DocumentQueryFacade;
import com.bytedance.ai.document.api.RagDocumentView;
import com.bytedance.ai.document.persistence.RagDocumentRepository;
import com.bytedance.ai.document.persistence.RagDocumentRecord;
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
