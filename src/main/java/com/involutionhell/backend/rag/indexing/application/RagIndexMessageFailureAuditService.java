package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.indexing.persistence.RagIndexMessageFailureRepository;
import com.involutionhell.backend.rag.indexing.service.RagIndexingMetrics;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagJsonCodec;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.stereotype.Service;

/**
 * 审计离线索引消息失败，尤其是反序列化失败等无法进入正常工作流的异常。
 */
@Service
public class RagIndexMessageFailureAuditService {

    private final RagIndexMessageFailureRepository failureRepository;
    private final RagIndexingMetrics indexingMetrics;
    private final RagProperties ragProperties;
    private final RagJsonCodec jsonCodec;

    public RagIndexMessageFailureAuditService(
            RagIndexMessageFailureRepository failureRepository,
            RagIndexingMetrics indexingMetrics,
            RagProperties ragProperties,
            RagJsonCodec jsonCodec
    ) {
        this.failureRepository = failureRepository;
        this.indexingMetrics = indexingMetrics;
        this.ragProperties = ragProperties;
        this.jsonCodec = jsonCodec;
    }

    public ParseFailureAuditResult recordParseFailure(MessageView messageView, byte[] payload, Exception exception) {
        String messageId = String.valueOf(messageView.getMessageId());
        String failureType = "parse";
        String errorMessage = abbreviate(exception.getMessage(), 500);
        String payloadBase64 = payload == null ? null : Base64.getEncoder().encodeToString(payload);
        String payloadPreview = payload == null
                ? null
                : abbreviate(new String(payload, StandardCharsets.UTF_8), ragProperties.rocketMq().parseFailurePayloadPreviewLength());
        Map<String, Object> properties = toProperties(messageView);
        String propertiesJson = jsonCodec.write(properties);

        failureRepository.save(
                messageId,
                messageView.getTopic(),
                normalizeAttempt(messageView.getDeliveryAttempt()),
                failureType,
                errorMessage,
                payloadBase64,
                payloadPreview,
                properties
        );

        int failureCount = failureRepository.countByMessageId(messageId);
        int threshold = ragProperties.rocketMq().parseFailureAlertThreshold();
        boolean thresholdReached = failureCount >= threshold;
        boolean alertRecommended = failureCount == threshold;
        indexingMetrics.recordMessageParseFailure(thresholdReached);
        return new ParseFailureAuditResult(messageId, failureCount, thresholdReached, alertRecommended, payloadPreview, propertiesJson, errorMessage);
    }

    private Map<String, Object> toProperties(MessageView messageView) {
        try {
            return jsonCodec.convertToMap(messageView.getProperties());
        } catch (Exception exception) {
            return Map.of("serializationError", abbreviate(exception.getMessage(), 120));
        }
    }

    private int normalizeAttempt(int deliveryAttempt) {
        return deliveryAttempt <= 0 ? 1 : deliveryAttempt;
    }

    private String abbreviate(String value, int maxLength) {
        return RagLogHelper.abbreviate(value, maxLength);
    }

    public record ParseFailureAuditResult(
            String messageId,
            int failureCount,
            boolean thresholdReached,
            boolean alertRecommended,
            String payloadPreview,
            String propertiesJson,
            String errorMessage
    ) {
    }
}
