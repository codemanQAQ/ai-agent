package com.bytedance.ai.retrieval.service;

import com.bytedance.ai.retrieval.api.RagResponseNoticeView;
import com.bytedance.ai.shared.metadata.RagSearchFilter;
import java.util.Set;

/**
 * Retriever 入参对象，携带统一预算，避免各检索分支自行重复放大候选集。
 */
public record RagRetrievalRequest(
        String query,
        RagSearchFilter filter,
        RagRetrievalBudget budget,
        Set<RagResponseNoticeView> feedbacks
) {

    public RagRetrievalRequest(String query, RagSearchFilter filter, RagRetrievalBudget budget) {
        this(query, filter, budget, null);
    }
}
