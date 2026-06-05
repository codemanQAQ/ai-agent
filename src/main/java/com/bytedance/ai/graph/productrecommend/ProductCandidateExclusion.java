package com.bytedance.ai.graph.productrecommend;

public record ProductCandidateExclusion(
        String productId,
        String skuId,
        String externalRef,
        String reason
) {

    static ProductCandidateExclusion of(ProductRecallCandidate candidate, String reason) {
        return new ProductCandidateExclusion(
                candidate.productId(),
                candidate.skuId(),
                candidate.externalRef(),
                reason
        );
    }
}
