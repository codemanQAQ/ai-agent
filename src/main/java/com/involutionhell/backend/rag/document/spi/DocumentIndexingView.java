package com.involutionhell.backend.rag.document.spi;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 对 indexing 模块暴露的文档索引视图。
 *
 * <p>这是一个跨模块协作 DTO，而不是 document 持久化记录本身。它的目标是让 indexing
 * 获得完成索引流程所需的信息，同时保持 document 模块拥有底层存储模型的演进自由。
 */
public record DocumentIndexingView(
        Long id,
        String sourceType,
        String sourceUri,
        String externalRef,
        String title,
        String content,
        String contentSha256,
        Long indexedGeneration,
        String status,
        Integer chunkCount,
        Integer attemptCount,
        Map<String, Object> metadata,
        String lastError,
        OffsetDateTime lastAttemptedAt,
        OffsetDateTime indexedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
