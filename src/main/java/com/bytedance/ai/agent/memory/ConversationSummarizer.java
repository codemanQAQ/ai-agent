package com.bytedance.ai.agent.memory;

import com.bytedance.ai.retrieval.spi.AgentTurnConversationState.ConversationTurn;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Component
public class ConversationSummarizer {

    public static final String MODEL_NAME = "agent-memory-summary-v1";
    private static final int MAX_SUMMARY_CHARS = 500;
    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizer.class);

    private final ObjectProvider<ChatModel> chatModelProvider;

    public ConversationSummarizer(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    public ConversationSummary summarize(
            List<ConversationTurn> history,
            Optional<String> previousSummary,
            Integer previousMessageCount
    ) {
        Optional<String> oldSummary = previousSummary == null ? Optional.empty() : previousSummary;
        int historySize = history == null ? 0 : history.size();
        int summarizableCount = Math.max(0, historySize - ConversationMemoryLoader.RECENT_TURN_LIMIT);
        if (summarizableCount <= 0) {
            return keepPrevious(oldSummary, previousMessageCount);
        }
        if (previousMessageCount != null && previousMessageCount >= summarizableCount) {
            return keepPrevious(oldSummary, previousMessageCount);
        }

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return keepPrevious(oldSummary, previousMessageCount);
        }

        try {
            String raw = ChatClient.create(chatModel)
                    .prompt()
                    .system("""
                            你是电商导购会话记忆摘要器。请把历史压缩成不超过 500 字的简体中文摘要。
                            只保留：用户偏好、预算、场景、已看过/明确排除的商品或品牌、仍待确认的信息。
                            不要编造，不要输出 Markdown 标题。
                            """)
                    .user(buildPrompt(history.subList(0, summarizableCount), oldSummary))
                    .call()
                    .content();
            String summary = normalizeSummary(raw);
            if (!StringUtils.hasText(summary)) {
                return keepPrevious(oldSummary, previousMessageCount);
            }
            return new ConversationSummary(Optional.of(summary), summarizableCount, MODEL_NAME);
        } catch (RuntimeException exception) {
            log.debug("conversation summary generation skipped: {}", RagLogHelper.errorSummary(exception));
            return keepPrevious(oldSummary, previousMessageCount);
        }
    }

    private ConversationSummary keepPrevious(Optional<String> oldSummary, Integer previousMessageCount) {
        return new ConversationSummary(oldSummary, previousMessageCount, oldSummary.isPresent() ? MODEL_NAME : null);
    }

    private String buildPrompt(List<ConversationTurn> turns, Optional<String> previousSummary) {
        StringBuilder builder = new StringBuilder();
        previousSummary.filter(StringUtils::hasText)
                .ifPresent(summary -> builder.append("旧摘要：\n").append(summary.trim()).append("\n\n"));
        builder.append("需压缩的历史：\n");
        for (ConversationTurn turn : turns) {
            builder.append("- ")
                    .append("user".equals(turn.role()) ? "用户" : "助手")
                    .append("：")
                    .append(turn.content() == null ? "" : turn.content().trim())
                    .append('\n');
        }
        return builder.toString();
    }

    private String normalizeSummary(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() <= MAX_SUMMARY_CHARS ? trimmed : trimmed.substring(0, MAX_SUMMARY_CHARS);
    }
}
