package com.bytedance.ai.graph.session;

import java.util.Map;

public record UserBehaviorFeedbackRequest(
        String userId,
        String conversationId,
        String runId,
        String productId,
        String skuId,
        String externalRef,
        String behaviorType,
        Integer rank,
        Map<String, Object> context
) {

    public UserBehaviorFeedbackRequest {
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
