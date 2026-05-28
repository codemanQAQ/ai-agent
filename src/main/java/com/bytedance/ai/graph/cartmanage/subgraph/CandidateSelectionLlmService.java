package com.bytedance.ai.graph.cartmanage.subgraph;

import com.bytedance.ai.graph.cartmanage.ProductCandidate;

import java.util.List;
import java.util.Optional;

public interface CandidateSelectionLlmService {

    Optional<Integer> resolveIndex(String userMessage, List<ProductCandidate> candidates);
}
