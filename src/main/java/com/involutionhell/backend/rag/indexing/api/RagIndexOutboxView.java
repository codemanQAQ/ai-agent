package com.involutionhell.backend.rag.indexing.api;

import java.time.OffsetDateTime;

/**
 * 当前文档版本对应的索引 outbox 视图。
 *
 * @param id outbox 主键
 * @param documentId 文档主键
 * @param contentSha256 文档版本哈希
 * @param eventType 事件类型
 * @param status 当前投递状态
 * @param attemptCount 已尝试投递次数
 * @param messageId 生产端发送成功后绑定的消息标识
 * @param lastError 最近一次投递失败信息
 * @param nextAttemptAt 下次投递时间
 * @param dispatchedAt 最近一次成功投递时间
 * @param consumedAt 消费端终态确认时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record RagIndexOutboxView(
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
