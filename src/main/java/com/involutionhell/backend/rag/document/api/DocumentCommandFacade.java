package com.involutionhell.backend.rag.document.api;

public interface DocumentCommandFacade {

    RagDocumentView createDocument(RagDocumentCreateRequest request);

    RagDocumentView updateDocument(Long documentId, RagDocumentUpdateRequest request);

    RagDocumentView reindexDocument(Long documentId);

    void deleteDocument(Long documentId);
}
