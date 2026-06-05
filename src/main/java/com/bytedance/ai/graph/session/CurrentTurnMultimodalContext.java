package com.bytedance.ai.graph.session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CurrentTurnMultimodalContext(
        String schemaVersion,
        List<String> inputModalities,
        String originalMessage,
        String message,
        String imageRef,
        String imageCaption,
        String imageEmbeddingRef,
        String queryTextForRecall,
        boolean hasImage,
        boolean imageFromHistory
) {

    public CurrentTurnMultimodalContext {
        inputModalities = inputModalities == null ? List.of() : List.copyOf(inputModalities);
    }

    public Map<String, Object> toStateMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("inputModalities", inputModalities);
        putIfPresent(value, "originalMessage", originalMessage);
        putIfPresent(value, "message", message);
        putIfPresent(value, "imageRef", imageRef);
        putIfPresent(value, "imageCaption", imageCaption);
        putIfPresent(value, "imageEmbeddingRef", imageEmbeddingRef);
        putIfPresent(value, "queryTextForRecall", queryTextForRecall);
        value.put("hasImage", hasImage);
        value.put("imageFromHistory", imageFromHistory);
        return value;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
