package com.bytedance.ai.graph.cartmanage.subgraph;

import com.bytedance.ai.graph.cartmanage.ProductCandidate;

import java.time.LocalDateTime;
import java.util.List;

public record PendingCartActionRecord(
        Long id,
        String userId,
        String conversationId,
        CartAction action,
        String productName,
        Integer quantity,
        List<ProductCandidate> candidates,
        CartWorkflowStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime expireAt
) {
}
