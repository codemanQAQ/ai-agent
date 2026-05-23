package com.bytedance.ai.agent.api.events;

public record TurnCompletedPayload(
        String turnId,
        Integer latencyMs,
        Integer tokensIn,
        Integer tokensOut,
        boolean generatedByModel
) {
}
