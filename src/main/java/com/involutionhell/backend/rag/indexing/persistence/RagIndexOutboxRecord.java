package com.involutionhell.backend.rag.indexing.persistence;

import java.time.OffsetDateTime;

/**
 * rag_index_outbox 表记录模型。
 *
 * @param id Outbox 事件主键
 * @param documentId 关联文档主键
 * @param contentSha256 关联文档内容哈希
 * @param eventType 事件类型
 * @param status 当前投递状态
 * @param attemptCount 已尝试投递次数
 * @param messageId 发送成功后绑定的生产端消息标识
 * @param lastError 最近一次投递失败信息
 * @param nextAttemptAt 下次可投递时间
 * @param dispatchedAt 最近一次成功投递时间
 * @param consumedAt 消费端完成终态处理的确认时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record RagIndexOutboxRecord(
        Long id,
        Long documentId,
        String contentSha256,
        String eventType,
        String status,
        Integer attemptCount,
        String messageId,
        String lastError,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime dispatchedAt,
        OffsetDateTime consumedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
