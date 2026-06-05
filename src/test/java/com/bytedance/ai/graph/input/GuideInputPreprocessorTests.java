package com.bytedance.ai.graph.input;

import com.bytedance.ai.graph.api.AgentTurnRequest;
import com.bytedance.ai.graph.api.GuideGraphRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuideInputPreprocessorTests {

    private final GuideInputPreprocessor preprocessor = new GuideInputPreprocessor();

    @Test
    void normalizesImageFields() {
        AgentTurnRequest request = new AgentTurnRequest(
                "user-1",
                "conversation-1",
                "  find cleanser  ",
                "turn-1",
                "request-1",
                "images/p1.jpg",
                "  a white bottle cleanser  ",
                "faiss:image:1",
                List.of()
        );

        GuideGraphRequest graphRequest = preprocessor.toGraphRequest(request);

        assertThat(graphRequest.message()).isEqualTo("find cleanser");
        assertThat(graphRequest.originalMessage()).isEqualTo("find cleanser");
        assertThat(graphRequest.imageRef()).isEqualTo("images/p1.jpg");
        assertThat(graphRequest.imageCaption()).isEqualTo("a white bottle cleanser");
        assertThat(graphRequest.imageEmbeddingRef()).isEqualTo("faiss:image:1");
        assertThat(graphRequest.inputModalities())
                .containsExactly("text", "image");
    }

    @Test
    void imageOnlyRequestGetsDefaultMessage() {
        AgentTurnRequest request = new AgentTurnRequest(
                "user-1",
                "conversation-1",
                null,
                "turn-1",
                "request-1",
                "images/p1.jpg",
                "caption",
                "embedding-ref",
                List.of()
        );

        GuideGraphRequest graphRequest = preprocessor.toGraphRequest(request);

        assertThat(graphRequest.message()).isNotBlank();
        assertThat(graphRequest.originalMessage()).isNull();
        assertThat(graphRequest.inputModalities()).containsExactly("image");
        assertThat(graphRequest.imageCaption()).isEqualTo("caption");
        assertThat(graphRequest.imageEmbeddingRef()).isEqualTo("embedding-ref");
    }
}
