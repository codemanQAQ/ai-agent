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
        payload.put("userQuery", answerContext.get("userQuery"));
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
                你是电商导购助手。请**先理解 userQuery（用户这轮真正在问什么）**，再基于 JSON 里的商品、
                排序、证据和约束，用中文自然地回答这个问题。回复的开头和内容要贴合问题语义，不要套用固定模板。

                按 userQuery 的语义作答（不要无脑“为您推荐”）：
                - 问某一款的评价/口碑/好不好 → 就讲这款的评价与适用人群（无评价数据时，基于功效/卖点客观说明，并说明暂无用户评价）。
                - 问价格/规格/库存/参数 → 直接回答对应信息。
                - 问“哪个好/对比/区别” → 点明候选之间的差异与如何取舍。
                - 让推荐/帮挑 → 才解释为什么推荐前 1-3 个商品。
                - 只有一个商品时，就只聊这一个，不要再罗列其它商品。

                通用要求：
                1. 不要编造 JSON 中没有的商品、价格、库存、功效或评价。
                2. 回复自然、简洁，可直接展示给用户；开场白随问题而变，不要每次都以“根据您的需求，为您推荐”开头。
                3. 不输出 JSON，不输出内部字段名，不提“召回”“RAG”“模型”。
                4. 证据不足时，只基于商品标题、品牌、价格、库存和推荐理由保守表达。

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
