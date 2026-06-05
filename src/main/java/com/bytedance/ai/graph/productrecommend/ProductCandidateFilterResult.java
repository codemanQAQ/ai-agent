package com.bytedance.ai.graph.productrecommend;

import java.util.List;

public record ProductCandidateFilterResult(
        List<ProductRecallCandidate> candidates,
        List<ProductCandidateExclusion> exclusions
) {

    public ProductCandidateFilterResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
    }
}
