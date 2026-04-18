package com.involutionhell.backend.rag.document.api;

import org.springframework.web.multipart.MultipartFile;

public interface DocumentCommandFacade {

    RagDocumentView createDocument(RagDocumentCreateRequest request);

    RagDocumentView createDocument(
            MultipartFile file,
            String sourceType,
            String sourceUri,
            String externalRef,
            String title,
            java.util.Map<String, Object> metadata
    );

    RagDocumentView updateDocument(Long documentId, RagDocumentUpdateRequest request);

    RagDocumentView reindexDocument(Long documentId);

    void deleteDocument(Long documentId);
}
