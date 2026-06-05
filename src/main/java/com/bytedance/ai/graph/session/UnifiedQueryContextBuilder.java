package com.bytedance.ai.graph.session;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UnifiedQueryContextBuilder {

    private final CandidateSnapshotReferenceResolver candidateSnapshotReferenceResolver =
            new CandidateSnapshotReferenceResolver();

    public UnifiedQueryContext build(AgentSessionState sessionState) {
        AgentSessionState source = sessionState == null
                ? new AgentSessionState("1.0", null, null, null, List.of(), null, null, null, null)
                : sessionState;
        RecommendationState recommendation = source.recommendationState();
        Map<String, Object> currentModal = source.multimodalState().current();
        String queryText = firstText(currentModal.get("queryTextForRecall"), currentModal.get("message"));
        Map<String, Object> positive = shouldResolveCandidateReference(recommendation.activeIntent())
                ? candidateSnapshotReferenceResolver.augment(
                recommendation.accumulatedConstraints(),
                recommendation.candidateSnapshot(),
                queryText
        )
                : recommendation.accumulatedConstraints();
        Map<String, Object> negative = recommendation.negativeConstraints();

        return new UnifiedQueryContext(
                "1.0",
                recommendation.activeIntent(),
                queryText,
                stringList(currentModal.get("inputModalities")),
                stringValue(currentModal.get("imageRef")),
                stringValue(currentModal.get("imageCaption")),
                stringValue(currentModal.get("imageEmbeddingRef")),
                positive,
                negative,
                buildScope(positive, recommendation.candidateSnapshot()),
                recommendation.candidateSnapshot(),
                !recommendation.missingSlots().isEmpty() || hasText(recommendation.clarifyQuestion()),
                recommendation.missingSlots(),
                recommendation.clarifyQuestion()
        );
    }

    private boolean shouldResolveCandidateReference(String activeIntent) {
        return "MULTI_TURN_REFINE".equals(activeIntent) || "PRODUCT_COMPARE".equals(activeIntent);
    }

    private UnifiedQueryScope buildScope(Map<String, Object> constraints, CandidateSnapshot candidateSnapshot) {
        Set<String> productIds = new LinkedHashSet<>();
        Set<String> externalRefs = new LinkedHashSet<>();
        Set<Long> catalogSpuIds = new LinkedHashSet<>();

        if (candidateSnapshot != null) {
            productIds.addAll(candidateSnapshot.productIds());
        }
        productIds.addAll(stringList(constraints.get("productIds")));
        externalRefs.addAll(stringList(constraints.get("externalRefs")));
        externalRefs.addAll(stringList(constraints.get("productRefs")));
        for (Object item : objectList(constraints.get("catalogSpuIds"))) {
            Long value = longValue(item);
            if (value != null) {
                catalogSpuIds.add(value);
            }
        }
        return new UnifiedQueryScope(
                List.copyOf(productIds),
                List.copyOf(externalRefs),
                List.copyOf(catalogSpuIds)
        );
    }

    private String firstText(Object first, Object second) {
        String value = stringValue(first);
        if (hasText(value)) {
            return value;
        }
        return stringValue(second);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private List<String> stringList(Object value) {
        List<String> values = new ArrayList<>();
        for (Object item : objectList(value)) {
            String text = stringValue(item);
            if (text != null) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    private List<?> objectList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
