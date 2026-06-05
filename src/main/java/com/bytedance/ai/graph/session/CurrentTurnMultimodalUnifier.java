package com.bytedance.ai.graph.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CurrentTurnMultimodalUnifier {

    public CurrentTurnMultimodalContext unify(
            AgentSessionState sessionState,
            String message,
            String originalMessage,
            List<String> inputModalities,
            String imageRef,
            String imageCaption,
            String imageEmbeddingRef
    ) {
        Map<String, Object> historicalImage = latestHistoricalImage(sessionState);
        boolean hasCurrentImage = hasText(imageRef) || hasText(imageCaption) || hasText(imageEmbeddingRef);
        boolean imageFromHistory = false;
        String resolvedImageRef = imageRef;
        String resolvedImageCaption = imageCaption;
        String resolvedImageEmbeddingRef = imageEmbeddingRef;

        if (!hasCurrentImage && !historicalImage.isEmpty() && referencesHistoryImage(message)) {
            resolvedImageRef = stringValue(historicalImage.get("imageRef"));
            resolvedImageCaption = stringValue(historicalImage.get("imageCaption"));
            resolvedImageEmbeddingRef = stringValue(historicalImage.get("imageEmbeddingRef"));
            hasCurrentImage = hasText(resolvedImageRef) || hasText(resolvedImageCaption) || hasText(resolvedImageEmbeddingRef);
            imageFromHistory = hasCurrentImage;
        }

        List<String> modalities = normalizeModalities(inputModalities, message, hasCurrentImage);
        return new CurrentTurnMultimodalContext(
                "1.0",
                modalities,
                blankToNull(originalMessage),
                blankToNull(message),
                blankToNull(resolvedImageRef),
                blankToNull(resolvedImageCaption),
                blankToNull(resolvedImageEmbeddingRef),
                buildQueryTextForRecall(message, resolvedImageCaption),
                hasCurrentImage,
                imageFromHistory
        );
    }

    private Map<String, Object> latestHistoricalImage(AgentSessionState sessionState) {
        if (sessionState == null || sessionState.multimodalState() == null) {
            return Map.of();
        }
        Map<String, Object> current = sessionState.multimodalState().current();
        if (current != null && !current.isEmpty()) {
            return current;
        }
        List<Map<String, Object>> history = sessionState.multimodalState().history();
        if (history == null || history.isEmpty()) {
            return Map.of();
        }
        return history.getLast();
    }

    private List<String> normalizeModalities(List<String> inputModalities, String message, boolean hasImage) {
        List<String> values = new ArrayList<>();
        if (inputModalities != null) {
            for (String modality : inputModalities) {
                if (hasText(modality) && !values.contains(modality)) {
                    values.add(modality);
                }
            }
        }
        if (hasText(message) && !values.contains("text")) {
            values.add("text");
        }
        if (hasImage && !values.contains("image")) {
            values.add("image");
        }
        return List.copyOf(values);
    }

    private String buildQueryTextForRecall(String message, String imageCaption) {
        List<String> parts = new ArrayList<>();
        if (hasText(message)) {
            parts.add(message.strip());
        }
        if (hasText(imageCaption)) {
            parts.add(imageCaption.strip());
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private boolean referencesHistoryImage(String message) {
        if (!hasText(message)) {
            return false;
        }
        String text = message.strip();
        return text.contains("图") || text.contains("照片") || text.contains("图片") || text.contains("这张")
                || text.contains("刚才") || text.toLowerCase().contains("image");
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String blankToNull(String value) {
        return hasText(value) ? value.strip() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
