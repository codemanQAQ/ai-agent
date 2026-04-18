package com.involutionhell.backend.rag.indexing.api;

import java.time.OffsetDateTime;

/**
 * 索引时间线内嵌的文档快照，避免 indexing API 反向依赖 document API。
 */
public record RagIndexTimelineDocumentView(
        Long id,
        String sourceType,
        String sourceUri,
        String externalRef,
        String title,
        String status,
        Integer chunkCount,
        Integer attemptCount,
        String lastError,
        OffsetDateTime lastAttemptedAt,
        OffsetDateTime indexedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
