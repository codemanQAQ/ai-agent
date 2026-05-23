package com.bytedance.ai.agent.api.events;

public record CitationPayload(
        String refId,
        Long spuId,
        Long chunkId
) {
}
