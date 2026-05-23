package com.bytedance.ai.agent.api.events;

public record TurnStartedPayload(
        String turnId,
        String conversationId,
        String model
) {
}
