package com.bytedance.ai.retrieval.service;

import com.bytedance.ai.shared.metadata.RagSearchFilter;
import java.util.List;

public interface RagRetrievalBudgetPlanner {

    RagRetrievalBudget plan(
            String originalQuestion,
            List<String> retrievalQueries,
            RagSearchFilter filter,
            int requestedTopK,
            boolean isRetry
    );
}
