package com.involutionhell.backend.rag.indexing.persistence;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * rag_index_job_transitions 表记录模型。
 *
 * @param id 审计主键
 * @param documentId 关联文档主键
 * @param jobId 关联索引作业主键
 * @param outboxId 关联 outbox 主键
 * @param contentSha256 文档版本哈希
 * @param fromState 跃迁前状态
 * @param toState 跃迁后状态
 * @param event 触发事件
 * @param triggerType 触发类型
 * @param triggeredBy 触发者标识
 * @param success 跃迁是否成功
 * @param failureReason 失败原因分类
 * @param errorMessage 详细错误信息
 * @param messageId 关联 MQ messageId
 * @param metadata 附加元数据
 * @param createdAt 创建时间
 */
public record RagIndexJobTransitionRecord(
        Long id,
        Long documentId,
        Long jobId,
        Long outboxId,
        String contentSha256,
        String fromState,
        String toState,
        String event,
        String triggerType,
        String triggeredBy,
        boolean success,
        String failureReason,
        String errorMessage,
        String messageId,
        Map<String, Object> metadata,
        OffsetDateTime createdAt
) {
}
