package com.bytedance.ai.graph.input;

import com.bytedance.ai.graph.api.AgentTurnRequest;
import com.bytedance.ai.graph.api.GuideGraphRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class GuideInputPreprocessor {

    private static final String IMAGE_ONLY_MESSAGE = "Find similar products from the image";

    public GuideGraphRequest toGraphRequest(AgentTurnRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String userText = normalize(request.message());
        String normalizedMessage = userText;
        String imageRef = normalize(request.imageRef());

        if (!StringUtils.hasText(normalizedMessage) && StringUtils.hasText(imageRef)) {
            normalizedMessage = IMAGE_ONLY_MESSAGE;
        }
        if (!StringUtils.hasText(normalizedMessage)) {
            throw new IllegalArgumentException("message or imageRef is required");
        }

        return new GuideGraphRequest(
                request.userId(),
                request.conversationId(),
                normalizedMessage,
                request.turnId(),
                request.requestId(),
                imageRef,
                normalize(request.imageCaption()),
                normalize(request.imageEmbeddingRef()),
                userText,
                inputModalities(userText, imageRef),
                null,
                null,
                request.history()
        );
    }

    private List<String> inputModalities(String userText, String imageRef) {
        List<String> modalities = new ArrayList<>();
        if (StringUtils.hasText(userText)) {
            modalities.add("text");
        }
        if (StringUtils.hasText(imageRef)) {
            modalities.add("image");
        }
        return List.copyOf(modalities);
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
