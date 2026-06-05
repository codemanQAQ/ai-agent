package com.bytedance.ai.graph.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Single-turn graph request.
 *
 * <p>ASR runs before this API. If the user input is audio, pass the recognized
 * text as {@code message}; the graph does not model audio as a modality.</p>
 */
public record AgentTurnRequest(
        @NotBlank(message = "userId must not be blank")
        @Size(max = 64, message = "userId max length is 64")
        String userId,
        @NotBlank(message = "conversationId must not be blank")
        @Size(max = 64, message = "conversationId max length is 64")
        String conversationId,
        @Size(max = 2000, message = "message max length is 2000")
        String message,
        @Size(max = 64, message = "turnId max length is 64")
        String turnId,
        @Size(max = 64, message = "requestId max length is 64")
        String requestId,
        @Size(max = 2048, message = "imageRef max length is 2048")
        String imageRef,
        @Size(max = 1000, message = "imageCaption max length is 1000")
        String imageCaption,
        @Size(max = 512, message = "imageEmbeddingRef max length is 512")
        String imageEmbeddingRef,
        List<@Valid AgentConversationTurn> history
) {
    public AgentTurnRequest {
        history = history == null ? List.of() : List.copyOf(history);
    }

    public AgentTurnRequest(
            String userId,
            String conversationId,
            String message,
            String turnId,
            String requestId,
            String imageRef,
            List<@Valid AgentConversationTurn> history
    ) {
        this(userId, conversationId, message, turnId, requestId, imageRef, null, null, history);
    }
}
