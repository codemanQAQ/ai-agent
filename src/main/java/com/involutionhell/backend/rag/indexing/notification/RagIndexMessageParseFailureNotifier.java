package com.involutionhell.backend.rag.indexing.notification;

/**
 * 当 RocketMQ 索引消息连续解析失败达到阈值时的外部告警通道。
 */
public interface RagIndexMessageParseFailureNotifier {

    void notifyParseFailureThresholdReached(
            String messageId,
            String topic,
            int deliveryAttempt,
            int failureCount,
            String errorMessage,
            String payloadPreview,
            String propertiesJson
    );
}
