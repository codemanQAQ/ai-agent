package com.bytedance.ai.graph.input;

import java.util.Optional;

public interface MultimodalInputProcessor {

    Optional<MultimodalInputProcessingResult> processImage(
            String imageRef,
            boolean generateCaption,
            boolean generateEmbedding
    );
}
