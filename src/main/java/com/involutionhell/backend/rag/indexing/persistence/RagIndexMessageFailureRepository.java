package com.involutionhell.backend.rag.indexing.persistence;

import java.util.Map;

/**
 * 离线索引消息失败审计仓储。
 */
public interface RagIndexMessageFailureRepository {

    /**
     * 写入一条消息失败审计记录。
     */
    void save(
            String messageId,
            String topic,
            int deliveryAttempt,
            String failureType,
            String errorMessage,
            String payloadBase64,
            String payloadPreview,
            Map<String, Object> properties
    );

    /**
     * 统计某条消息累计的失败次数。
     */
    int countByMessageId(String messageId);
}
