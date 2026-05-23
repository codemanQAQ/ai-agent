package com.bytedance.ai.agent.answer;

import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.memory.ConversationMemory;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState.ConversationTurn;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

@Service
public class AgentAnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(AgentAnswerGenerator.class);
    private static final String FALLBACK_PROMPT_TEMPLATE = """
            你是电商导购助手。仅依据下方【候选商品】回答用户。
            推荐商品时必须用 [#N] 标记，N 等于商品列表里的序号。
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final Resource promptTemplateResource;

    public AgentAnswerGenerator(
            ObjectProvider<ChatModel> chatModelProvider,
            @Value("classpath:prompts/agent-answer-v1.txt") Resource promptTemplateResource
    ) {
        this.chatModelProvider = chatModelProvider;
        this.promptTemplateResource = promptTemplateResource;
    }

    public Flux<String> generateStream(
            String message,
            List<SpuCardView> cards,
            Consumer<Boolean> generatedByModelCallback
    ) {
        return generateStream(message, cards, ConversationMemory.empty(), generatedByModelCallback);
    }

    public Flux<String> generateStream(
            String message,
            List<SpuCardView> cards,
            ConversationMemory memory,
            Consumer<Boolean> generatedByModelCallback
    ) {
        return generateStream(message, cards, null, memory, generatedByModelCallback);
    }

    public Flux<String> generateStream(
            String message,
            List<SpuCardView> cards,
            CompareMatrixView compareMatrix,
            ConversationMemory memory,
            Consumer<Boolean> generatedByModelCallback
    ) {
        if (cards == null || cards.isEmpty()) {
            generatedByModelCallback.accept(false);
            return Flux.just("目前没有完全匹配的商品。你可以补充预算、品牌、使用场景或必须具备的功能，我再帮你缩小范围。");
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            generatedByModelCallback.accept(false);
            return Flux.just(fallbackAnswer(message, cards, compareMatrix));
        }
        try {
            Flux<String> stream = ChatClient.create(chatModel)
                    .prompt()
                    .system(promptTemplate())
                    .user(buildUserPrompt(message, cards, compareMatrix, memory))
                    .stream()
                    .content()
                    .doOnSubscribe(ignored -> generatedByModelCallback.accept(true))
                    .filter(StringUtils::hasText);
            return stream.switchIfEmpty(Flux.defer(() -> {
                        generatedByModelCallback.accept(false);
                        return Flux.just(fallbackAnswer(message, cards, compareMatrix));
                    }))
                    .onErrorResume(exception -> {
                        log.warn("agent answer generation failed, falling back: error={}", RagLogHelper.errorSummary(exception));
                        generatedByModelCallback.accept(false);
                        return Flux.just(fallbackAnswer(message, cards, compareMatrix));
                    });
        } catch (RuntimeException exception) {
            log.warn("agent answer generation setup failed, falling back: error={}", RagLogHelper.errorSummary(exception));
            generatedByModelCallback.accept(false);
            return Flux.just(fallbackAnswer(message, cards, compareMatrix));
        }
    }

    private String promptTemplate() {
        try {
            return promptTemplateResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            log.warn("agent answer prompt resource unavailable, using fallback: error={}", RagLogHelper.errorSummary(exception));
            return FALLBACK_PROMPT_TEMPLATE;
        }
    }

    private String buildUserPrompt(String message, List<SpuCardView> cards, CompareMatrixView compareMatrix, ConversationMemory memory) {
        StringBuilder builder = new StringBuilder();
        if (memory != null && memory.summary().isPresent()) {
            builder.append("【会话摘要】\n").append(memory.summary().get().trim()).append("\n\n");
        }
        if (memory != null && !memory.recentMessages().isEmpty()) {
            builder.append("【最近对话】\n");
            for (ConversationTurn turn : memory.recentMessages()) {
                builder.append("- ")
                        .append("user".equals(turn.role()) ? "用户" : "助手")
                        .append("：")
                        .append(nullToEmpty(turn.content()).trim())
                        .append("\n");
            }
            builder.append("\n");
        }
        builder.append("【候选商品】\n");
        for (int i = 0; i < cards.size(); i++) {
            SpuCardView card = cards.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(nullToEmpty(card.title()))
                    .append("（")
                    .append(nullToEmpty(card.brand()))
                    .append("）")
                    .append(" - 价格：")
                    .append(priceText(card))
                    .append("，库存：")
                    .append(card.stock() == null ? "未知" : card.stock())
                    .append("\n   ")
                    .append(card.reasons().isEmpty() ? "" : card.reasons().getFirst())
                    .append("\n");
        }
        if (compareMatrix != null) {
            builder.append("\n【对比矩阵】\n");
            appendMarkdownTable(builder, compareMatrix);
            if (StringUtils.hasText(compareMatrix.recommendationReason())) {
                builder.append("推荐理由：").append(withCitationRefs(compareMatrix.recommendationReason())).append("\n");
            }
        }
        builder.append("\n【用户消息】\n").append(message);
        return builder.toString();
    }

    private String fallbackAnswer(String message, List<SpuCardView> cards) {
        return fallbackAnswer(message, cards, null);
    }

    private String fallbackAnswer(String message, List<SpuCardView> cards, CompareMatrixView compareMatrix) {
        if (compareMatrix != null && compareMatrix.rows().size() > 0) {
            StringBuilder builder = new StringBuilder();
            builder.append("根据“").append(message).append("”，对比如下：\n\n");
            appendMarkdownTable(builder, compareMatrix);
            if (StringUtils.hasText(compareMatrix.recommendedRefId())) {
                builder.append("\n推荐：").append(citationRef(compareMatrix.recommendedRefId()));
                if (StringUtils.hasText(compareMatrix.recommendationReason())) {
                    builder.append("，").append(withCitationRefs(compareMatrix.recommendationReason()));
                }
                builder.append("。");
            }
            return builder.toString();
        }
        StringBuilder builder = new StringBuilder();
        builder.append("根据“").append(message).append("”，我先推荐：");
        for (int i = 0; i < Math.min(cards.size(), 3); i++) {
            SpuCardView card = cards.get(i);
            if (i > 0) {
                builder.append("；");
            }
            builder.append(" [#").append(i + 1).append("] ")
                    .append(nullToEmpty(card.title()))
                    .append("，")
                    .append(priceText(card));
        }
        builder.append("。价格和库存以商品卡片为准。");
        return builder.toString();
    }

    private void appendMarkdownTable(StringBuilder builder, CompareMatrixView compareMatrix) {
        builder.append("| 属性 |");
        for (CompareMatrixView.ProductColumn product : compareMatrix.products()) {
            builder.append(' ').append(citationRef(product.refId())).append(' ').append(nullToEmpty(product.title())).append(" |");
        }
        builder.append("\n|---|");
        for (int i = 0; i < compareMatrix.products().size(); i++) {
            builder.append("---|");
        }
        builder.append('\n');
        for (CompareMatrixView.AttributeRow row : compareMatrix.rows()) {
            builder.append("| ").append(nullToEmpty(row.attribute())).append(" |");
            for (String value : row.values()) {
                builder.append(' ').append(nullToEmpty(value)).append(" |");
            }
            builder.append('\n');
        }
    }

    private String priceText(SpuCardView card) {
        if (card.priceMin() == null && card.priceMax() == null) {
            return "价格未知";
        }
        if (card.priceMin() == null) {
            return "¥" + card.priceMax();
        }
        if (card.priceMax() == null || card.priceMin().compareTo(card.priceMax()) == 0) {
            return "¥" + card.priceMin();
        }
        return "¥" + card.priceMin() + "-¥" + card.priceMax();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String withCitationRefs(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return text.replaceAll("(?<!\\[)(#\\d+)(?!\\])", "[$1]");
    }

    private String citationRef(String refId) {
        if (!StringUtils.hasText(refId)) {
            return "";
        }
        String trimmed = refId.trim();
        return trimmed.startsWith("[") ? trimmed : "[" + trimmed + "]";
    }
}
