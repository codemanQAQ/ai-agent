package com.bytedance.ai.graph.productrecommend;

import java.util.List;

public record ProductRecallPlan(
        ProductRecommendSubScene subScene,
        List<ProductRecallSource> enabledSources,
        int perSourceLimit,
        int outputLimit,
        boolean enforcePositiveConstraints,
        String description
) {

    public ProductRecallPlan {
        enabledSources = enabledSources == null ? List.of() : List.copyOf(enabledSources);
        perSourceLimit = perSourceLimit <= 0 ? 10 : Math.min(perSourceLimit, 100);
        outputLimit = outputLimit <= 0 ? 5 : Math.min(outputLimit, 50);
    }
}
