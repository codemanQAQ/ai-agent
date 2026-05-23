package com.bytedance.ai.agent.api.events;

public record ToolCallingPayload(
        String toolName,
        Object args
) {
}
