package com.bytedance.ai.graph.api;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

public record GuideGraphRequest(
        String userId,
        String conversationId,
        String message,
        String runId,
        String requestId,
        String imageRef,
        String imageCaption,
        String imageEmbeddingRef,
        String originalMessage,
        List<String> inputModalities,
        String correlationId,
        GuideGraphIntent initialIntent,
        List<?> history
) {
    public GuideGraphRequest {
        runId = StringUtils.hasText(runId) ? runId : UUID.randomUUID().toString();
        requestId = StringUtils.hasText(requestId) ? requestId : UUID.randomUUID().toString();
        correlationId = StringUtils.hasText(correlationId) ? correlationId : requestId;
        inputModalities = inputModalities == null ? List.of() : List.copyOf(inputModalities);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public GuideGraphRequest(
            String userId,
            String conversationId,
            String message,
            String runId,
            String requestId,
            String imageRef,
            String correlationId,
            GuideGraphIntent initialIntent,
            List<?> history
    ) {
        this(
                userId,
                conversationId,
                message,
                runId,
                requestId,
                imageRef,
                null,
                null,
                message,
                defaultModalities(message, imageRef),
                correlationId,
                initialIntent,
                history
        );
    }

    public static GuideGraphRequest from(AgentTurnRequest request) {
        return from(request, null);
    }

    /**
     * @deprecated Controller must not provide intent. Keep this only for tests and legacy callers
     * that intentionally seed graph state.
     */
    @Deprecated(forRemoval = false)
    public static GuideGraphRequest from(AgentTurnRequest request, GuideGraphIntent initialIntent) {
        return new GuideGraphRequest(
                request.userId(),
                request.conversationId(),
                request.message(),
                request.turnId(),
                request.requestId(),
                request.imageRef(),
                request.imageCaption(),
                request.imageEmbeddingRef(),
                request.message(),
                defaultModalities(request.message(), request.imageRef()),
                null,
                initialIntent,
                request.history()
        );
    }

    private static List<String> defaultModalities(String message, String imageRef) {
        java.util.ArrayList<String> modalities = new java.util.ArrayList<>();
        if (StringUtils.hasText(message)) {
            modalities.add("text");
        }
        if (StringUtils.hasText(imageRef)) {
            modalities.add("image");
        }
        return List.copyOf(modalities);
    }
}
