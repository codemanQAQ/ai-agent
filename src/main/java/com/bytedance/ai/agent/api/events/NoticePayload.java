package com.bytedance.ai.agent.api.events;

public record NoticePayload(
        String code,
        String message,
        String severity
) {
}
