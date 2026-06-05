package com.bytedance.ai.graph.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSessionStateMergerTest {

    private final AgentSessionStateMerger merger = new AgentSessionStateMerger();

    @Test
    void mergesNonEmptyConstraintsAndKeepsExistingValuesWhenPatchIsEmpty() {
        AgentSessionState base = new AgentSessionState(
                "1.0",
                "user-1",
                "conversation-1",
                Instant.now(),
                List.of(),
                new RecommendationState(
                        "FUZZY_RECOMMEND",
                        "通勤",
                        Map.of("category", "包", "scenario", "通勤"),
                        Map.of("attributes", List.of("太重")),
                        List.of("price"),
                        "预算大概是多少？",
                        CandidateSnapshot.empty(),
                        LastRecommendationResult.empty()
                ),
                MultimodalState.empty(),
                CartState.empty(),
                OrderState.empty()
        );

        AgentSessionState merged = merger.merge(
                base,
                "CONDITION_FILTER",
                Map.of(
                        "positiveConstraints",
                        Map.of("category", "双肩包", "brand", "", "priceMax", 300),
                        "negativeConstraints",
                        Map.of("reviewSignals", List.of("掉色"))
                ),
                List.of(),
                null,
                null
        );

        assertThat(merged.recommendationState().activeIntent()).isEqualTo("CONDITION_FILTER");
        assertThat(merged.recommendationState().accumulatedConstraints())
                .containsEntry("category", "双肩包")
                .containsEntry("scenario", "通勤")
                .containsEntry("priceMax", 300)
                .doesNotContainKey("brand");
        assertThat(merged.recommendationState().negativeConstraints())
                .containsEntry("attributes", List.of("太重"))
                .containsEntry("reviewSignals", List.of("掉色"));
        assertThat(merged.recommendationState().missingSlots()).isEmpty();
        assertThat(merged.recommendationState().clarifyQuestion()).isNull();
    }

    @Test
    void writesCurrentMultimodalContextAndMovesPreviousCurrentToHistory() {
        AgentSessionState base = new AgentSessionState(
                "1.0",
                "user-1",
                "conversation-1",
                Instant.now(),
                List.of(),
                RecommendationState.empty(),
                new MultimodalState(
                        Map.of("imageRef", "old.jpg", "hasImage", true),
                        List.of()
                ),
                CartState.empty(),
                OrderState.empty()
        );
        CurrentTurnMultimodalContext current = new CurrentTurnMultimodalContext(
                "1.0",
                List.of("text", "image"),
                "找类似图片里的包",
                "找类似图片里的包",
                "new.jpg",
                "黑色通勤双肩包",
                "vec-1",
                "找类似图片里的包\n黑色通勤双肩包",
                true,
                false
        );

        AgentSessionState merged = merger.merge(
                base,
                "PHOTO_SEARCH",
                Map.of(),
                List.of(),
                null,
                current
        );

        assertThat(merged.multimodalState().current())
                .containsEntry("imageRef", "new.jpg")
                .containsEntry("imageCaption", "黑色通勤双肩包")
                .containsEntry("hasImage", true);
        assertThat(merged.multimodalState().history()).hasSize(1);
        assertThat(merged.multimodalState().history().getFirst())
                .containsEntry("imageRef", "old.jpg");
    }
}
