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
        // 只有「多轮细化」才在历史累积上叠加；其它意图（换品类/换场景/对比/拍照等）视为新请求，
        // 以当轮约束为准、重置累积，避免上一轮的 audience/attributes/brand/price 等残留污染本轮。
        boolean refine = "MULTI_TURN_REFINE".equalsIgnoreCase(activeIntent);
        Map<String, Object> accumulatedConstraints = refine
                ? mergeNonEmpty(source.accumulatedConstraints(), positivePatch)
                : mergeNonEmpty(null, positivePatch);
        Map<String, Object> negativeConstraints = refine
                ? mergeNonEmpty(source.negativeConstraints(), negativePatch)
                : mergeNonEmpty(null, negativePatch);
        String scenario = refine
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

    private Map<String, Object> mergeNonEmpty(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (patch == null) {
            return Map.copyOf(merged);
        }
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (hasValue(entry.getValue())) {
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
