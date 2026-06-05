package com.bytedance.ai.graph.session;

import java.time.Instant;
import java.util.Map;

public record UserBehaviorFeedbackResult(
        String feedbackId,
        UserBehaviorType behaviorType,
        String userId,
        String productId,
        String skuId,
        String externalRef,
        Map<String, Object> preferenceMemory,
        Instant acceptedAt
) {

    public UserBehaviorFeedbackResult {
        preferenceMemory = preferenceMemory == null ? Map.of() : Map.copyOf(preferenceMemory);
    }
}
