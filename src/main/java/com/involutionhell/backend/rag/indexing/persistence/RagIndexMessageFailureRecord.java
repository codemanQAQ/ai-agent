package com.involutionhell.backend.rag.indexing.persistence;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * rag_index_message_failures 表记录模型。
 */
public record RagIndexMessageFailureRecord(
        Long id,
        String messageId,
        String topic,
        Integer deliveryAttempt,
        String failureType,
        String errorMessage,
        String payloadBase64,
        String payloadPreview,
        Map<String, Object> properties,
        OffsetDateTime createdAt
) {
}
