package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.CandidateSnapshot;

import java.util.List;

public record ProductCandidatePostProcessResult(
        List<ProductRecallCandidate> candidates,
        List<ProductCandidateExclusion> exclusions,
        CandidateSnapshot candidateSnapshot
) {

    public ProductCandidatePostProcessResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        candidateSnapshot = candidateSnapshot == null ? CandidateSnapshot.empty() : candidateSnapshot;
    }
}
