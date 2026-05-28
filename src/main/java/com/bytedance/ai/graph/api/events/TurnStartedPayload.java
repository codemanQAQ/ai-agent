package com.bytedance.ai.graph.api.events;

public record TurnStartedPayload(
        String turnId,
        String conversationId,
        String model
) {
}
