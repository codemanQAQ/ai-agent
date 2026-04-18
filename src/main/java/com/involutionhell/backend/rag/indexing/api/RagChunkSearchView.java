package com.involutionhell.backend.rag.indexing.api;

import java.util.Map;

/**
 * retrieval 需要的切片检索视图。
 */
public record RagChunkSearchView(
        Long chunkId,
        Long documentId,
        String title,
        String sourceType,
        String sourceUri,
        String externalRef,
        Long indexGeneration,
        Integer chunkIndex,
        String chunkText,
        String vectorId,
        Map<String, Object> metadata
) {
}
