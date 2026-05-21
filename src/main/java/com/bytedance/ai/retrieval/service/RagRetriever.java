package com.bytedance.ai.retrieval.service;

import com.bytedance.ai.retrieval.model.RagRetrievedChunk;
import com.bytedance.ai.shared.metadata.RagSearchFilter;
import java.util.List;

/**
 * 检索层抽象。
 */
public interface RagRetriever {

    List<RagRetrievedChunk> search(RagRetrievalRequest request);

    @Deprecated
    default List<RagRetrievedChunk> search(String question, int topK, RagSearchFilter filter) {
        return search(new RagRetrievalRequest(question, filter, RagRetrievalBudget.legacy(topK)));
    }
}
