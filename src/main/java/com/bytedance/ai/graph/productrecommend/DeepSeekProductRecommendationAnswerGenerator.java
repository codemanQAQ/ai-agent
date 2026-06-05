package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.shared.support.RagJsonCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

@Service
public class DeepSeekProductRecommendationAnswerGenerator implements ProductRecommendationAnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekProductRecommendationAnswerGenerator.class);
    private static final int MAX_PRODUCTS = 6;
    private static final int MAX_EVIDENCE = 8;

    private final ChatClient chatClient;
    private final RagJsonCodec jsonCodec;

    public DeepSeekProductRecommendationAnswerGenerator(
            @Qualifier("intentChatClient") ChatClient chatClient,
            RagJsonCodec jsonCodec
    ) {
        this.chatClient = chatClient;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Optional<String> generate(Map<String, Object> answerContext, String fallbackAnswer) {
        if (answerContext == null || !hasProducts(answerContext)) {
            return Optional.empty();
        }
        try {
            // 复用 intentChatClient 的默认选项（已含 thinking=disabled + maxTokens 等，即“意图识别做法”）。
            String content = chatClient.prompt(prompt(answerContext, fallbackAnswer))
                    .call()
                    .content();
            if (!StringUtils.hasText(content)) {
                return Optional.empty();
            }
            return Optional.of(content.trim());
        } catch (Exception exception) {
            log.warn("DeepSeek product recommendation answer generation failed, fallback used: {}",
                    exception.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Flux<String> generateStream(Map<String, Object> answerContext, String fallbackAnswer) {
        if (answerContext == null || !hasProducts(answerContext)) {
            return Flux.empty();
        }
        // 复用 intentChatClient 默认选项（thinking=disabled + maxTokens），仅切换为流式。
        return chatClient.prompt(prompt(answerContext, fallbackAnswer))
                .stream()
                .content()
                .onErrorResume(exception -> {
                    log.warn("DeepSeek streaming recommendation answer failed, fallback used: {}",
                            exception.getMessage());
                    return Flux.empty();
                });
    }

    private boolean hasProducts(Map<String, Object> answerContext) {
        Object products = answerContext.get("products");
        return products instanceof List<?> list && !list.isEmpty();
    }

    private String prompt(Map<String, Object> answerContext, String fallbackAnswer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", answerContext.get("intent"));
        payload.put("targetWorkflow", answerContext.get("targetWorkflow"));
        payload.put("products", firstItems(answerContext.get("products"), MAX_PRODUCTS));
        putIfPresent(payload, "recommendationReasons", answerContext.get("recommendationReasons"));
        putIfPresent(payload, "matchedSlots", answerContext.get("matchedSlots"));
        putIfPresent(payload, "missingSlots", answerContext.get("missingSlots"));
        putIfPresent(payload, "comparison", answerContext.get("comparison"));
        putIfPresent(payload, "bundle", answerContext.get("bundle"));
        payload.put("evidence", firstItems(answerContext.get("evidence"), MAX_EVIDENCE));
        payload.put("fallbackAnswer", fallbackAnswer);

        return """
                你是电商导购助手。请基于下面 JSON 中的最终召回商品、排序、证据和用户约束，生成给用户看的中文回复。

                要求：
                1. 不要编造 JSON 中没有的商品、价格、库存、功效或评价。
                2. 优先解释为什么推荐前 1-3 个商品；如果有对比或场景组合，也要点明差异或组合角色。
                3. 回复要自然、简洁，可直接展示给用户。
                4. 不输出 JSON，不输出内部字段名，不提“召回”“RAG”“模型”。
                5. 如果证据不足，只基于商品标题、品牌、价格、库存和推荐理由保守表达。

                输入 JSON：
                %s
                """.formatted(jsonCodec.write(payload));
    }

    private List<?> firstItems(Object value, int limit) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream().limit(limit).toList();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
