package com.involutionhell.backend.rag.indexing.messaging;

import com.involutionhell.backend.rag.indexing.application.RagIndexMessageFailureAuditService;
import com.involutionhell.backend.rag.indexing.application.RagIndexSuccessStateService;
import com.involutionhell.backend.rag.indexing.application.RagIndexTerminalStateService;
import com.involutionhell.backend.rag.indexing.application.RagIndexingService;
import com.involutionhell.backend.rag.indexing.model.RagIndexAttemptException;
import com.involutionhell.backend.rag.indexing.model.RagIndexFailure;
import com.involutionhell.backend.rag.indexing.notification.RagFinalFailureEmailNotifier;
import com.involutionhell.backend.rag.indexing.notification.RagIndexMessageParseFailureNotifier;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRepository;
import com.involutionhell.backend.rag.indexing.service.RagIndexingFailureClassifier;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowCommand;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowTriggerType;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagJsonCodec;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 处理 RocketMQ 中的 RAG 索引消息。
 *
 * <p>该监听器负责解析消息、执行一次索引尝试，并根据失败类型决定
 * 是交给 RocketMQ 继续重试，还是直接落最终失败状态。
 */
@Component
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "rag.rocketmq", name = {"endpoints", "topic"})
@RocketMQMessageListener(
        endpoints = "${rag.rocketmq.endpoints:}",
        topic = "${rag.rocketmq.topic:}",
        tag = "*",
        consumerGroup = "${rag.rocketmq.consumer-group:rag-index-consumer}"
)
public class RagIndexMessageListener implements RocketMQListener {

    private static final Logger log = LoggerFactory.getLogger(RagIndexMessageListener.class);

    private final RagJsonCodec jsonCodec;
    private final RagProperties ragProperties;
    private final RagIndexingFailureClassifier failureClassifier;
    private final RagIndexingService ragIndexingService;
    private final RagIndexTerminalStateService terminalStateService;
    private final RagIndexSuccessStateService ragIndexSuccessStateService;
    private final IndexWorkflowService workflowService;
    private final RagIndexOutboxRepository outboxRepository;
    private final RagIndexMessageFailureAuditService messageFailureAuditService;
    private final ObjectProvider<RagIndexMessageParseFailureNotifier> parseFailureNotifierProvider;
    private final ObjectProvider<RagFinalFailureEmailNotifier> finalFailureEmailNotifierProvider;

    public RagIndexMessageListener(
            RagJsonCodec jsonCodec,
            RagProperties ragProperties,
            RagIndexingFailureClassifier failureClassifier,
            RagIndexingService ragIndexingService,
            RagIndexTerminalStateService terminalStateService,
            RagIndexSuccessStateService ragIndexSuccessStateService,
            IndexWorkflowService workflowService,
            RagIndexOutboxRepository outboxRepository,
            RagIndexMessageFailureAuditService messageFailureAuditService,
            ObjectProvider<RagIndexMessageParseFailureNotifier> parseFailureNotifierProvider,
            ObjectProvider<RagFinalFailureEmailNotifier> finalFailureEmailNotifierProvider
    ) {
        this.jsonCodec = jsonCodec;
        this.ragProperties = ragProperties;
        this.failureClassifier = failureClassifier;
        this.ragIndexingService = ragIndexingService;
        this.terminalStateService = terminalStateService;
        this.ragIndexSuccessStateService = ragIndexSuccessStateService;
        this.workflowService = workflowService;
        this.outboxRepository = outboxRepository;
        this.messageFailureAuditService = messageFailureAuditService;
        this.parseFailureNotifierProvider = parseFailureNotifierProvider;
        this.finalFailureEmailNotifierProvider = finalFailureEmailNotifierProvider;
    }

    @Override
    public ConsumeResult consume(MessageView messageView) {
        RagIndexMessage message = null;
        int deliveryAttempt = normalizeDeliveryAttempt(messageView.getDeliveryAttempt());
        String messageId = messageView.getMessageId().toString();
        byte[] bytes = null;
        try {
            // RocketMQ 消息体以 ByteBuffer 暴露，这里先复制再做 JSON 反序列化。
            ByteBuffer body = messageView.getBody().duplicate();
            bytes = new byte[body.remaining()];
            body.get(bytes);
            message = jsonCodec.read(new String(bytes, StandardCharsets.UTF_8), RagIndexMessage.class);
            // 将 messageId 绑定到 job，方便后续从库表和日志一起追踪一次消费。
            log.info(
                    "RAG index message received: messageId={}, documentId={}, contentSha={}, deliveryAttempt={}",
                    messageView.getMessageId(),
                    message.documentId(),
                    RagLogHelper.shortSha(message.contentSha256()),
                    deliveryAttempt
            );
            workflowService.attachMessageId(message.documentId(), message.contentSha256(), messageId);
            ragIndexingService.indexDocument(
                    message.documentId(),
                    message.contentSha256(),
                    command(message, messageId, deliveryAttempt)
            );

            try {
                ragIndexSuccessStateService.confirmConsumedOrThrow(
                        message.documentId(),
                        message.contentSha256(),
                        messageId
                );
            } catch (Exception confirmException) {
                log.error(
                        "RAG index message succeeded but outbox confirmation failed, MQ will retry: messageId={}, documentId={}, contentSha={}, error={}",
                        messageView.getMessageId(),
                        message.documentId(),
                        RagLogHelper.shortSha(message.contentSha256()),
                        RagLogHelper.errorSummary(confirmException),
                        confirmException
                );
                return ConsumeResult.FAILURE;
            }

            log.debug(
                    "RAG index message handled successfully: messageId={}, documentId={}, contentSha={}",
                    messageView.getMessageId(),
                    message.documentId(),
                    RagLogHelper.shortSha(message.contentSha256())
            );
            return ConsumeResult.SUCCESS;
        } catch (IllegalArgumentException exception) {
            if (message != null) {
                IndexWorkflowCommand skipCommand = command(message, messageId, deliveryAttempt)
                        .withNote(exception.getMessage())
                        .withFailure("invalid", exception.getMessage());
                terminalStateService.skipAndConfirmConsumed(skipCommand);
            }
            log.warn(
                    "RAG index message ignored because document is unavailable: messageId={}, error={}",
                    messageView.getMessageId(),
                    RagLogHelper.errorSummary(exception)
            );
            return ConsumeResult.SUCCESS;
        } catch (RagIndexAttemptException exception) {
            return handleIndexFailure(messageView, message, messageId, deliveryAttempt, exception);
        } catch (Exception exception) {
            if (message == null) {
                RagIndexMessageFailureAuditService.ParseFailureAuditResult audit =
                        messageFailureAuditService.recordParseFailure(messageView, bytes, exception);
                if (audit.thresholdReached()) {
                    confirmConsumptionByMessageId(audit.messageId());
                    if (audit.alertRecommended()) {
                        RagIndexMessageParseFailureNotifier notifier = parseFailureNotifierProvider.getIfAvailable();
                        if (notifier != null) {
                            notifier.notifyParseFailureThresholdReached(
                                    audit.messageId(),
                                    messageView.getTopic(),
                                    deliveryAttempt,
                                    audit.failureCount(),
                                    audit.errorMessage(),
                                    audit.payloadPreview(),
                                    audit.propertiesJson()
                            );
                        }
                    }
                    log.error(
                            "RAG index message parsing failed repeatedly and is now acknowledged for manual intervention: messageId={}, deliveryAttempt={}, failureCount={}, topic={}, payloadPreview={}",
                            messageView.getMessageId(),
                            deliveryAttempt,
                            audit.failureCount(),
                            messageView.getTopic(),
                            RagLogHelper.abbreviate(audit.payloadPreview(), 160),
                            exception
                    );
                    return ConsumeResult.SUCCESS;
                }
                log.error(
                        "RAG index message parsing failed and will be retried by MQ: messageId={}, deliveryAttempt={}, error={}",
                        messageView.getMessageId(),
                        deliveryAttempt,
                        RagLogHelper.errorSummary(exception),
                        exception
                );
                return ConsumeResult.FAILURE;
            }
            RagIndexFailure failure = failureClassifier.classify(exception);
            return handleIndexFailure(
                    messageView,
                    message,
                    messageId,
                    deliveryAttempt,
                    failure.retryable(),
                    failure.reason(),
                    "索引失败 [" + failure.reason() + "]: " + abbreviate(exception.getMessage()),
                    exception
            );
        }
    }

    /**
     * 处理索引服务抛出的结构化失败结果。
     */
    private ConsumeResult handleIndexFailure(
            MessageView messageView,
            RagIndexMessage message,
            String messageId,
            int deliveryAttempt,
            RagIndexAttemptException exception
    ) {
        return handleIndexFailure(
                messageView,
                message,
                messageId,
                deliveryAttempt,
                exception.isRetryable(),
                exception.getReason(),
                exception.getErrorMessage(),
                exception
        );
    }

    /**
     * 统一处理监听器中的失败分支。
     *
     * <p>可重试错误返回 {@link ConsumeResult#FAILURE} 交给 RocketMQ 接管，
     * 不可重试或已到达最大投递次数的错误则直接落最终失败状态。
     */
    private ConsumeResult handleIndexFailure(
            MessageView messageView,
            RagIndexMessage message,
            String messageId,
            int deliveryAttempt,
            boolean retryable,
            String reason,
            String errorMessage,
            Exception exception
    ) {
        int maxAttempts = ragProperties.indexing().maxRetries() + 1;
        IndexWorkflowCommand command = command(message, messageId, deliveryAttempt)
                .withFailure(reason, errorMessage);
        if (retryable && deliveryAttempt < maxAttempts) {
            // 可重试错误不在本地阻塞等待，直接交回 RocketMQ 走延迟重试。
            workflowService.retry(command);
            log.warn(
                    "RAG index message failed and will be retried by MQ: messageId={}, documentId={}, contentSha={}, deliveryAttempt={}/{}, reason={}, error={}",
                    messageView.getMessageId(),
                    message.documentId(),
                    RagLogHelper.shortSha(message.contentSha256()),
                    deliveryAttempt,
                    maxAttempts,
                    reason,
                    RagLogHelper.errorSummary(exception)
            );
            return ConsumeResult.FAILURE;
        }

        String finalErrorMessage = retryable
                ? errorMessage + "（RocketMQ 已达到最大投递次数 " + deliveryAttempt + "/" + maxAttempts + "）"
                : errorMessage;
        IndexWorkflowCommand finalCommand = command.withFailure(reason, finalErrorMessage);

        // 进入这里说明 MQ 已经不再适合兜底，需要把最终失败状态和 outbox 消费确认在同一事务里落库。
        terminalStateService.failAndConfirmConsumed(finalCommand);

        RagFinalFailureEmailNotifier notifier = finalFailureEmailNotifierProvider.getIfAvailable();
        if (notifier != null) {
            try {
                notifier.notifyFinalFailure(
                        message.documentId(),
                        message.contentSha256(),
                        finalErrorMessage,
                        maxAttempts,
                        messageId
                );
            } catch (Exception notifyException) {
                // 通知失败不应影响消费结果，仅记录告警
                log.warn(
                        "RAG final failure email notification failed: messageId={}, documentId={}, error={}",
                        messageId,
                        message.documentId(),
                        RagLogHelper.errorSummary(notifyException)
                );
            }
        }

        if (retryable) {
            log.error(
                    "RAG index message reached max retry attempts and is now failed: messageId={}, documentId={}, contentSha={}, deliveryAttempt={}/{}, reason={}",
                    messageView.getMessageId(),
                    message.documentId(),
                    RagLogHelper.shortSha(message.contentSha256()),
                    deliveryAttempt,
                    maxAttempts,
                    reason,
                    exception
            );
        } else {
            log.error(
                    "RAG index message failed permanently: messageId={}, documentId={}, contentSha={}, reason={}",
                    messageView.getMessageId(),
                    message.documentId(),
                    RagLogHelper.shortSha(message.contentSha256()),
                    reason,
                    exception
            );
        }
        return ConsumeResult.SUCCESS;
    }

    private void confirmConsumptionByMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        try {
            outboxRepository.confirmConsumedByMessageId(messageId);
        } catch (Exception exception) {
            log.warn(
                    "RAG outbox consumption confirmation by messageId failed: messageId={}, error={}",
                    messageId,
                    RagLogHelper.errorSummary(exception)
            );
        }
    }

    private IndexWorkflowCommand command(RagIndexMessage message, String messageId, int deliveryAttempt) {
        return IndexWorkflowCommand.of(
                        message.documentId(),
                        message.contentSha256(),
                        IndexWorkflowTriggerType.MQ,
                        ragProperties.rocketMq().consumerGroup()
                )
                .withMessageId(messageId)
                .withMetadata("deliveryAttempt", deliveryAttempt)
                .withMetadata("requestedAt", message.requestedAt());
    }

    private int normalizeDeliveryAttempt(int deliveryAttempt) {
        return deliveryAttempt <= 0 ? 1 : deliveryAttempt;
    }

    /**
     * 截断异常消息，避免过长文本污染状态表和日志。
     */
    private String abbreviate(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        if (message.length() <= 240) {
            return message;
        }
        return message.substring(0, 237) + "...";
    }
}
