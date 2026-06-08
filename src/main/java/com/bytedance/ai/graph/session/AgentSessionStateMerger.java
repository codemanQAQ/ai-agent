package com.bytedance.ai.graph.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentSessionStateMerger {

    public AgentSessionState merge(
            AgentSessionState base,
            String activeIntent,
            Map<String, Object> intentSlots,
            List<String> missingSlots,
            String clarifyQuestion,
            CurrentTurnMultimodalContext multimodalContext
    ) {
        AgentSessionState source = base == null
                ? new AgentSessionState("1.0", null, null, Instant.now(), List.of(), null, null, null, null)
                : base;
        RecommendationState recommendationState = mergeRecommendationState(
                source.recommendationState(),
                activeIntent,
                intentSlots,
                missingSlots,
                clarifyQuestion
        );
        MultimodalState multimodalState = mergeMultimodalState(
                source.multimodalState(),
                multimodalContext
        );
        return new AgentSessionState(
                source.schemaVersion(),
                source.userId(),
                source.conversationId(),
                Instant.now(),
                source.recentMessages(),
                recommendationState,
                multimodalState,
                source.cartState(),
                source.orderState()
        );
    }

    private RecommendationState mergeRecommendationState(
            RecommendationState base,
            String activeIntent,
            Map<String, Object> intentSlots,
            List<String> missingSlots,
            String clarifyQuestion
    ) {
        RecommendationState source = base == null ? RecommendationState.empty() : base;
        Map<String, Object> positivePatch = typedMap(intentSlots == null ? null : intentSlots.get("positiveConstraints"));
        Map<String, Object> negativePatch = typedMap(intentSlots == null ? null : intentSlots.get("negativeConstraints"));

        // 非推荐类意图（购物车/下单/闲聊/澄清等）不触碰推荐上下文：原样保留上一轮的品类与累积约束，
        // 避免一次加购或闲聊把"正在聊的品类"抹掉，导致之后的纯属性追问无类目可继承。
        if (!isRecommendationIntent(activeIntent)) {
            return new RecommendationState(
                    source.activeIntent(),
                    source.scenario(),
                    source.accumulatedConstraints(),
                    source.negativeConstraints(),
                    source.missingSlots(),
                    nonBlankString(clarifyQuestion, null),
                    source.candidateSnapshot(),
                    source.lastRecommendationResult()
            );
        }

        // 推荐类意图：是否点名了新品类？点名新品类→视为新请求、重置累积；未点名（纯价格/属性/肤质/
        // 人群/场景/偏好等）→视为对最近一次推荐的细化，累积叠加并继承上一轮品类。这不依赖意图标签
        // 恰好为 MULTI_TURN_REFINE，故对"任何没有点名品类的后续约束"都成立。
        boolean namesNewCategory = hasValue(positivePatch.get("category")) || hasValue(positivePatch.get("subCategory"));
        boolean accumulate = !namesNewCategory;
        Map<String, Object> accumulatedConstraints = accumulate
                ? mergeNonEmpty(source.accumulatedConstraints(), positivePatch)
                : mergeNonEmpty(null, positivePatch);
        Map<String, Object> negativeConstraints = accumulate
                ? mergeNonEmpty(source.negativeConstraints(), negativePatch)
                : mergeNonEmpty(null, negativePatch);
        String scenario = accumulate
                ? nonBlankString(positivePatch.get("scenario"), source.scenario())
                : nonBlankString(positivePatch.get("scenario"), null);

        return new RecommendationState(
                nonBlankString(activeIntent, source.activeIntent()),
                scenario,
                accumulatedConstraints,
                negativeConstraints,
                missingSlots == null ? source.missingSlots() : missingSlots,
                nonBlankString(clarifyQuestion, null),
                source.candidateSnapshot(),
                source.lastRecommendationResult()
        );
    }

    private MultimodalState mergeMultimodalState(
            MultimodalState base,
            CurrentTurnMultimodalContext multimodalContext
    ) {
        MultimodalState source = base == null ? MultimodalState.empty() : base;
        if (multimodalContext == null) {
            return source;
        }
        Map<String, Object> current = multimodalContext.toStateMap();
        List<Map<String, Object>> history = new ArrayList<>(source.history());
        if (!source.current().isEmpty() && !source.current().equals(current)) {
            history.add(source.current());
        }
        return new MultimodalState(current, history);
    }

    /**
     * 是否推荐链路意图（会产生/细化商品推荐）。购物车、下单、闲聊、澄清、政策问答等不属于此类，
     * 它们不应改写推荐上下文。未知意图按推荐处理（保守，仍走"未点名品类则继承"的逻辑）。
     */
    private boolean isRecommendationIntent(String activeIntent) {
        if (activeIntent == null || activeIntent.isBlank()) {
            return false;
        }
        return switch (activeIntent.toUpperCase(java.util.Locale.ROOT)) {
            case "CART_MANAGE", "ADD_TO_CART", "REMOVE_FROM_CART", "UPDATE_CART_ITEM",
                 "ORDER_MANAGE", "CREATE_ORDER", "CONFIRM_ORDER", "CANCEL_ORDER",
                 "ORDER_QUERY", "LOGISTICS_QUERY",
                 "SMALL_TALK", "OTHER", "CLARIFY", "UNKNOWN", "POLICY_QA" -> false;
            default -> true;
        };
    }

    private Map<String, Object> mergeNonEmpty(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (patch == null) {
            return Map.copyOf(merged);
        }
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (!hasValue(entry.getValue())) {
                continue;
            }
            Object baseValue = merged.get(entry.getKey());
            // 列表值做并集去重（如负向 brands：不要oppo + 不要vivo → [oppo, vivo]；正向多值属性同理），
            // 标量值取最新。否则后一轮只给单个值会覆盖掉前几轮累积的约束。
            if (baseValue instanceof List<?> baseList && entry.getValue() instanceof List<?> patchList) {
                java.util.LinkedHashSet<Object> union = new java.util.LinkedHashSet<>(baseList);
                union.addAll(patchList);
                merged.put(entry.getKey(), new java.util.ArrayList<>(union));
            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(merged));
    }

    private Map<String, Object> typedMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null && item != null) {
                typed.put(String.valueOf(key), item);
            }
        });
        return java.util.Collections.unmodifiableMap(typed);
    }

    private boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private String nonBlankString(Object preferred, String fallback) {
        if (preferred == null) {
            return fallback;
        }
        String value = String.valueOf(preferred);
        return value.isBlank() ? fallback : value;
    }
}
