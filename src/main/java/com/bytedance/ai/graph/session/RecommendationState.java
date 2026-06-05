package com.bytedance.ai.graph.session;

import java.util.List;
import java.util.Map;

public record RecommendationState(
        String activeIntent,
        String scenario,
        Map<String, Object> accumulatedConstraints,
        Map<String, Object> negativeConstraints,
        List<String> missingSlots,
        String clarifyQuestion,
        CandidateSnapshot candidateSnapshot,
        LastRecommendationResult lastRecommendationResult
) {

    public RecommendationState {
        accumulatedConstraints = accumulatedConstraints == null ? Map.of() : Map.copyOf(accumulatedConstraints);
        negativeConstraints = negativeConstraints == null ? Map.of() : Map.copyOf(negativeConstraints);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
        candidateSnapshot = candidateSnapshot == null ? CandidateSnapshot.empty() : candidateSnapshot;
        lastRecommendationResult = lastRecommendationResult == null
                ? LastRecommendationResult.empty()
                : lastRecommendationResult;
    }

    public static RecommendationState empty() {
        return new RecommendationState(
                null,
                null,
                Map.of(),
                Map.of(),
                List.of(),
                null,
                CandidateSnapshot.empty(),
                LastRecommendationResult.empty()
        );
    }
}
