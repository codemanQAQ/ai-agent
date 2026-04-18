package com.involutionhell.backend.rag.document.persistence;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * rag_documents 表的记录模型。
 *
 * @param id 文档主键
 * @param sourceType 来源类型
 * @param sourceUri 来源 URI
 * @param externalRef 外部引用标识
 * @param title 文档标题
 * @param content 文档原始内容
 * @param contentSha256 文档内容哈希
 * @param indexedGeneration 当前生效的索引 generation
 * @param status 文档索引状态
 * @param chunkCount 当前有效切片数量
 * @param attemptCount 累计索引尝试次数
 * @param metadata 文档 metadata
 * @param lastError 最近一次失败信息
 * @param lastAttemptedAt 最近一次尝试时间
 * @param indexedAt 最近一次索引成功时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record RagDocumentRecord(
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
