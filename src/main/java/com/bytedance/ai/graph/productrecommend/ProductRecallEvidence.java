package com.bytedance.ai.graph.productrecommend;

import java.util.Map;

public record ProductRecallEvidence(
        ProductRecallSource source,
        String evidenceType,
        String title,
        String content,
        String chunkId,
        String parentChunkId,
        String productId,
        Map<String, Object> metadata
) {

    public ProductRecallEvidence {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
