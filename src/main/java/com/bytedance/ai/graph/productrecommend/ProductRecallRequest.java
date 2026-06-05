package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.UnifiedQueryContext;

public record ProductRecallRequest(
        UnifiedQueryContext queryContext,
        int limit,
        ProductRecallPlan plan
) {

    public ProductRecallRequest {
        limit = limit <= 0 ? 10 : Math.min(limit, 100);
    }

    public ProductRecallRequest(UnifiedQueryContext queryContext, int limit) {
        this(queryContext, limit, null);
    }
}
