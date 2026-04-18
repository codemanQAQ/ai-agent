package com.involutionhell.backend.rag.indexing.notification;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 当 RocketMQ 索引消息反序列化失败达到阈值时，发送外部告警邮件。
 */
@Service
@ConditionalOnBean(JavaMailSender.class)
@ConditionalOnProperty(prefix = "rag.notification.message-parse-failure-email", name = "enabled", havingValue = "true")
public class RagIndexMessageParseFailureEmailNotifier implements RagIndexMessageParseFailureNotifier {

    private static final Logger log = LoggerFactory.getLogger(RagIndexMessageParseFailureEmailNotifier.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);

    private final JavaMailSender mailSender;
    private final String from;
    private final String recipients;
    private final String subjectPrefix;

    public RagIndexMessageParseFailureEmailNotifier(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${rag.notification.message-parse-failure-email.from:}") String from,
            @Value("${rag.notification.message-parse-failure-email.recipients:}") String recipients,
            @Value("${rag.notification.message-parse-failure-email.subject-prefix:[RAG]}") String subjectPrefix
    ) {
        this.mailSender = Objects.requireNonNull(
                mailSenderProvider.getIfAvailable(),
                "JavaMailSender bean is required when parse failure email notifier is enabled"
        );
        this.from = from;
        this.recipients = recipients;
        this.subjectPrefix = subjectPrefix;
    }

    @Override
    public void notifyParseFailureThresholdReached(
            String messageId,
            String topic,
            int deliveryAttempt,
            int failureCount,
            String errorMessage,
            String payloadPreview,
            String propertiesJson
    ) {
        String[] resolvedRecipients = Arrays.stream(recipients.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
        if (resolvedRecipients.length == 0) {
            log.warn(
                    "Skip parse failure alert email because no recipients are configured: messageId={}, topic={}",
                    messageId,
                    topic
            );
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (StringUtils.hasText(from)) {
                message.setFrom(from);
            }
            message.setTo(resolvedRecipients);
            message.setSubject(buildSubject(topic));
            message.setText(buildBody(messageId, topic, deliveryAttempt, failureCount, errorMessage, payloadPreview, propertiesJson));
            mailSender.send(message);
            log.warn(
                    "RAG parse failure alert email sent: messageId={}, topic={}, recipientCount={}",
                    messageId,
                    topic,
                    resolvedRecipients.length
            );
        } catch (Exception exception) {
            log.warn(
                    "Failed to send parse failure alert email: messageId={}, topic={}, error={}",
                    messageId,
                    topic,
                    exception.getMessage()
            );
        }
    }

    private String buildSubject(String topic) {
        String prefix = StringUtils.hasText(subjectPrefix) ? subjectPrefix.trim() + " " : "";
        return prefix + "RocketMQ 索引消息解析失败达到阈值: " + topic;
    }

    private String buildBody(
            String messageId,
            String topic,
            int deliveryAttempt,
            int failureCount,
            String errorMessage,
            String payloadPreview,
            String propertiesJson
    ) {
        StringBuilder body = new StringBuilder();
        body.append("你好，\n\n");
        body.append("有一条 RocketMQ 索引消息在应用侧连续解析失败，并已达到告警阈值。\n\n");
        body.append("消息信息：\n");
        body.append("- Message ID: ").append(defaultString(messageId, "-")).append('\n');
        body.append("- Topic: ").append(defaultString(topic, "-")).append('\n');
        body.append("- 当前投递次数: ").append(deliveryAttempt).append('\n');
        body.append("- 累计解析失败次数: ").append(failureCount).append('\n');
        body.append("- 最近错误: ").append(defaultString(errorMessage, "未知错误")).append('\n');
        body.append("- Payload Preview: ").append(defaultString(payloadPreview, "-")).append('\n');
        body.append("- Properties JSON: ").append(defaultString(propertiesJson, "{}")).append('\n');
        body.append("- 告警时间: ")
                .append(OffsetDateTime.now().atZoneSameInstant(ZoneId.systemDefault()).format(TIMESTAMP_FORMATTER))
                .append("\n\n");
        body.append("建议：\n");
        body.append("1. 检查消息发布方与消费方的 payload 结构是否一致。\n");
        body.append("2. 检查是否存在脏消息、兼容性变更或序列化配置漂移。\n");
        body.append("3. 确认死信队列或人工补偿流程是否需要介入。\n");
        return body.toString();
    }

    private String defaultString(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
