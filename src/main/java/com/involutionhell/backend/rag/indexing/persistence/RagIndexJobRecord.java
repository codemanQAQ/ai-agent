package com.involutionhell.backend.rag.indexing.persistence;

import java.time.OffsetDateTime;

/**
 * rag_index_jobs 表的记录模型。
 *
 * @param id 作业主键
 * @param documentId 关联文档主键
 * @param contentSha256 对应文档版本哈希
 * @param status 作业状态
 * @param stage 当前处理阶段
 * @param version 状态版本号
 * @param lastEvent 最近一次触发的工作流事件
 * @param attemptCount 已执行尝试次数
 * @param targetGeneration 目标索引 generation
 * @param messageId 关联 MQ messageId
 * @param lastError 最近一次错误信息
 * @param startedAt 当前尝试开始时间
 * @param finishedAt 最后一次完成时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record RagIndexJobRecord(
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
