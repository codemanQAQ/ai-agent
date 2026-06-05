package com.bytedance.ai.graph.input;

import java.util.Map;

public record MultimodalInputProcessingResult(
        String imageCaption,
        String imageEmbeddingRef,
        Map<String, Object> metadata
) {
    public MultimodalInputProcessingResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
