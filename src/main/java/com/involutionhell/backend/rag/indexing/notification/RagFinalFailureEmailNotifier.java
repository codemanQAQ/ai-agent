package com.involutionhell.backend.rag.indexing.notification;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import com.involutionhell.backend.rag.shared.support.RagJsonCodec;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * 当离线索引在 MQ 最大尝试次数后仍失败时，向文档作者发送通知邮件。
 */
@Service
@ConditionalOnBean(JavaMailSender.class)
@ConditionalOnProperty(prefix = "rag.notification.final-failure-email", name = "enabled", havingValue = "true")
public class RagFinalFailureEmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(RagFinalFailureEmailNotifier.class);
    private static final List<String> EMAIL_KEYS = List.of(
            "authorEmail",
            "author_email",
            "notifyEmail",
            "notify_email",
            "notificationEmail",
            "notification_email",
            "email"
    );
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);

    private final DocumentIndexingSpi documentIndexingSpi;
    private final JavaMailSender mailSender;
    private final RagJsonCodec jsonCodec;
    private final String from;
    private final String subjectPrefix;
    private final String baseUrl;

    public RagFinalFailureEmailNotifier(
            DocumentIndexingSpi documentIndexingSpi,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            RagJsonCodec jsonCodec,
            @Value("${rag.notification.final-failure-email.from:}") String from,
            @Value("${rag.notification.final-failure-email.subject-prefix:[RAG]}") String subjectPrefix,
            @Value("${rag.notification.final-failure-email.base-url:}") String baseUrl
    ) {
        this.documentIndexingSpi = documentIndexingSpi;
        this.mailSender = Objects.requireNonNull(
                mailSenderProvider.getIfAvailable(),
                "JavaMailSender bean is required when final failure email notifier is enabled"
        );
        this.jsonCodec = jsonCodec;
        this.from = from;
        this.subjectPrefix = subjectPrefix;
        this.baseUrl = baseUrl;
    }

    public void notifyFinalFailure(
            Long documentId,
            String contentSha256,
            String errorMessage,
            int maxAttempts,
            String messageId
    ) {
        DocumentIndexingView document = documentIndexingSpi.findById(documentId).orElse(null);
        if (document == null) {
            log.warn("Skip final failure email because document is missing: documentId={}", documentId);
            return;
        }
        if (StringUtils.hasText(contentSha256)
                && StringUtils.hasText(document.contentSha256())
                && !contentSha256.equals(document.contentSha256())) {
            log.info(
                    "Skip final failure email because document version has changed: documentId={}, staleSha={}, currentSha={}",
                    documentId,
                    RagLogHelper.shortSha(contentSha256),
                    RagLogHelper.shortSha(document.contentSha256())
            );
            return;
        }

        Map<String, Object> metadata = parseMetadata(document.metadata());
        String recipient = resolveAuthorEmail(metadata);
        if (!StringUtils.hasText(recipient)) {
            log.info(
                    "Skip final failure email because no author email was found in document metadata: documentId={}, contentSha={}",
                    documentId,
                    RagLogHelper.shortSha(document.contentSha256())
            );
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (StringUtils.hasText(from)) {
                message.setFrom(from);
            }
            message.setTo(recipient);
            message.setSubject(buildSubject(document));
            message.setText(buildBody(document, errorMessage, maxAttempts, messageId));
            mailSender.send(message);
            log.info(
                    "RAG final failure email sent: documentId={}, contentSha={}, recipient={}",
                    documentId,
                    RagLogHelper.shortSha(document.contentSha256()),
                    recipient
            );
        } catch (Exception exception) {
            log.warn(
                    "Failed to send RAG final failure email: documentId={}, recipient={}, error={}",
                    documentId,
                    recipient,
                    RagLogHelper.errorSummary(exception)
            );
        }
    }

    private Map<String, Object> parseMetadata(Map<String, Object> metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Object> metadata = new LinkedHashMap<>(metadataJson);
            return metadata == null ? Map.of() : metadata;
        } catch (Exception exception) {
            log.warn(
                    "Failed to parse document metadata for final failure email: error={}, payload={}",
                    RagLogHelper.errorSummary(exception),
                    RagLogHelper.abbreviate(String.valueOf(metadataJson), 200)
            );
            return Map.of();
        }
    }

    private String resolveAuthorEmail(Map<String, Object> metadata) {
        String directEmail = firstEmail(metadata);
        if (StringUtils.hasText(directEmail)) {
            return directEmail;
        }

        String nestedAuthorEmail = nestedAuthorEmail(metadata);
        if (StringUtils.hasText(nestedAuthorEmail)) {
            return nestedAuthorEmail;
        }

        Map<String, Object> frontmatter = asMap(metadata.get("frontmatter"));
        if (frontmatter.isEmpty()) {
            return null;
        }

        String frontmatterEmail = firstEmail(frontmatter);
        if (StringUtils.hasText(frontmatterEmail)) {
            return frontmatterEmail;
        }

        return nestedAuthorEmail(frontmatter);
    }

    private String firstEmail(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        for (String key : EMAIL_KEYS) {
            String value = asText(source.get(key));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String nestedAuthorEmail(Map<String, Object> source) {
        Map<String, Object> author = asMap(source.get("author"));
        if (author.isEmpty()) {
            return null;
        }
        return firstEmail(author);
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return normalized;
        }
        return Map.of();
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String buildSubject(DocumentIndexingView document) {
        String prefix = StringUtils.hasText(subjectPrefix) ? subjectPrefix.trim() + " " : "";
        String title = StringUtils.hasText(document.title()) ? document.title().trim() : "未命名文档";
        return prefix + "文档离线索引最终失败: " + title;
    }

    private String buildBody(
            DocumentIndexingView document,
            String errorMessage,
            int maxAttempts,
            String messageId
    ) {
        StringBuilder body = new StringBuilder();
        body.append("你好，\n\n");
        body.append("你提交的 RAG 文档在离线索引链路中已达到最大尝试次数，仍未成功完成入库。\n\n");
        body.append("文档信息：\n");
        body.append("- 文档 ID: ").append(document.id()).append('\n');
        body.append("- 标题: ").append(defaultString(document.title(), "未命名文档")).append('\n');
        body.append("- 来源 URI: ").append(defaultString(document.sourceUri(), "-")).append('\n');
        body.append("- 内容版本: ").append(defaultString(document.contentSha256(), "-")).append('\n');
        body.append("- 最大尝试次数: ").append(maxAttempts).append('\n');
        body.append("- MQ Message ID: ").append(defaultString(messageId, "-")).append('\n');
        body.append("- 最终错误: ").append(defaultString(errorMessage, "未知错误")).append('\n');
        body.append("- 更新时间: ").append(formatTimestamp(document.updatedAt())).append("\n\n");
        if (StringUtils.hasText(baseUrl)) {
            body.append("排查链接：\n");
            body.append(baseUrl.replaceAll("/+$", ""))
                    .append("/public/rag/documents/index-timeline/")
                    .append(document.id())
                    .append("\n\n");
        }
        body.append("建议：\n");
        body.append("1. 检查文档内容与 metadata 是否符合当前离线链路要求。\n");
        body.append("2. 检查 embedding / Milvus / RocketMQ 等外部依赖的可用性与参数限制。\n");
        body.append("3. 修复后重新触发文档重建索引。\n\n");
        body.append("这是一封系统自动通知邮件。");
        return body.toString();
    }

    private String defaultString(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String formatTimestamp(OffsetDateTime value) {
        if (value == null) {
            return "-";
        }
        return value.atZoneSameInstant(ZoneId.systemDefault()).format(TIMESTAMP_FORMATTER);
    }
}
