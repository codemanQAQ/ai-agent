package com.bytedance.ai.agent.api.events;

public record TurnErrorPayload(
        String code,
        String message,
        boolean recoverable
) {
}
