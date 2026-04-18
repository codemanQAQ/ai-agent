package com.involutionhell.backend.rag.indexing.api;

import java.time.OffsetDateTime;

/**
 * 当前文档版本对应的离线索引作业视图。
 *
 * @param id 作业主键
 * @param documentId 文档主键
 * @param contentSha256 文档版本哈希
 * @param status 作业状态
 * @param stage 当前阶段
 * @param version 当前状态版本号
 * @param lastEvent 最近一次工作流事件
 * @param attemptCount 已尝试次数
 * @param targetGeneration 目标 generation
 * @param messageId 对应 MQ messageId
 * @param lastError 最近一次错误信息
 * @param startedAt 当前尝试开始时间
 * @param finishedAt 最近一次完成时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record RagIndexJobView(
        Long id,
        Long documentId,
        String contentSha256,
        String status,
        String stage,
        Long version,
        String lastEvent,
        Integer attemptCount,
        Long targetGeneration,
        String messageId,
        String lastError,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
