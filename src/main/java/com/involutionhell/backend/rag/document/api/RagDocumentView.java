package com.involutionhell.backend.rag.document.api;

import java.time.OffsetDateTime;

/**
 * 文档索引状态视图。
 *
 * @param id 文档主键
 * @param sourceType 来源类型
 * @param sourceUri 来源 URI
 * @param externalRef 外部引用标识
 * @param title 文档标题
 * @param status 当前索引状态
 * @param chunkCount 当前切片数量
 * @param attemptCount 累计尝试次数
 * @param lastError 最近一次错误信息
 * @param lastAttemptedAt 最近一次尝试时间
 * @param indexedAt 最近一次索引成功时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record RagDocumentView(
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
