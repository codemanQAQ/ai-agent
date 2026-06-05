package com.bytedance.ai.graph.session;

import java.util.List;
import java.util.Map;

public record LastRecommendationResult(
        List<Map<String, Object>> products
) {

    public LastRecommendationResult {
        products = products == null ? List.of() : List.copyOf(products);
    }

    public static LastRecommendationResult empty() {
        return new LastRecommendationResult(List.of());
    }
}
