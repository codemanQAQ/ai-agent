package com.involutionhell.backend.rag.indexing.messaging;

import java.time.OffsetDateTime;

/**
 * RocketMQ 索引消息体。
 *
 * @param documentId 目标文档主键
 * @param contentSha256 目标文档版本哈希
 * @param requestedAt 请求入队时间
 */
public record RagIndexMessage(Long documentId, String contentSha256, OffsetDateTime requestedAt) {
}
