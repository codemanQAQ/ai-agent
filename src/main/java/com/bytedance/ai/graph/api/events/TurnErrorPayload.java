package com.bytedance.ai.graph.api.events;

public record TurnErrorPayload(
        String code,
        String message,
        boolean recoverable
) {
}
