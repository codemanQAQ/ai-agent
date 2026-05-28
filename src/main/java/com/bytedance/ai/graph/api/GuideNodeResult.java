package com.bytedance.ai.graph.api;

import java.time.Instant;
import java.util.Map;

public record GuideNodeResult(
        String nodeName,
        NodeRunStatus status,
        GuideGraphIntent routeIntent,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Map<String, Object> metadata
) {
    public GuideNodeResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
