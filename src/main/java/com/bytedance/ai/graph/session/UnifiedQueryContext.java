package com.bytedance.ai.graph.session;

import java.util.List;
import java.util.Map;

public record UnifiedQueryContext(
        String schemaVersion,
        String intent,
        String queryText,
        List<String> inputModalities,
        String imageRef,
        String imageCaption,
        String imageEmbeddingRef,
        Map<String, Object> positiveConstraints,
        Map<String, Object> negativeConstraints,
        UnifiedQueryScope scope,
        CandidateSnapshot candidateSnapshot,
        boolean needClarify,
        List<String> missingSlots,
        String clarifyQuestion
) {

    public UnifiedQueryContext {
        inputModalities = inputModalities == null ? List.of() : List.copyOf(inputModalities);
        positiveConstraints = positiveConstraints == null ? Map.of() : Map.copyOf(positiveConstraints);
        negativeConstraints = negativeConstraints == null ? Map.of() : Map.copyOf(negativeConstraints);
        scope = scope == null ? UnifiedQueryScope.empty() : scope;
        candidateSnapshot = candidateSnapshot == null ? CandidateSnapshot.empty() : candidateSnapshot;
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }
}
