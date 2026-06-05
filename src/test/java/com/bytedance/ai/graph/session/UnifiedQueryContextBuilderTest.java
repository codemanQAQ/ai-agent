package com.bytedance.ai.graph.session;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedQueryContextBuilderTest {

    private final UnifiedQueryContextBuilder builder = new UnifiedQueryContextBuilder();

    @Test
    void buildsRecallContextFromMergedSessionState() {
        AgentSessionState sessionState = new AgentSessionState(
                "1.0",
                "user-1",
                "conversation-1",
                Instant.now(),
                List.of(),
                new RecommendationState(
                        "CONDITION_FILTER",
                        "通勤",
                        Map.of(
                                "category", "双肩包",
                                "priceMax", 300,
                                "productRefs", List.of("p_bag_001"),
                                "catalogSpuIds", List.of(101, "102")
                        ),
                        Map.of("attributes", List.of("太重")),
                        List.of(),
                        null,
                        new CandidateSnapshot(List.of("p_bag_002"), Instant.now()),
                        LastRecommendationResult.empty()
                ),
                new MultimodalState(
                        Map.of(
                                "inputModalities", List.of("text", "image"),
                                "queryTextForRecall", "找通勤双肩包\n图片里是黑色背包",
                                "imageRef", "bag.jpg",
                                "imageCaption", "黑色通勤双肩包",
                                "imageEmbeddingRef", "vec-1"
                        ),
                        List.of()
                ),
                CartState.empty(),
                OrderState.empty()
        );

        UnifiedQueryContext context = builder.build(sessionState);

        assertThat(context.intent()).isEqualTo("CONDITION_FILTER");
        assertThat(context.queryText()).contains("找通勤双肩包", "黑色背包");
        assertThat(context.inputModalities()).containsExactly("text", "image");
        assertThat(context.imageRef()).isEqualTo("bag.jpg");
        assertThat(context.positiveConstraints())
                .containsEntry("category", "双肩包")
                .containsEntry("priceMax", 300);
        assertThat(context.negativeConstraints()).containsEntry("attributes", List.of("太重"));
        assertThat(context.scope().productIds()).containsExactly("p_bag_002");
        assertThat(context.scope().externalRefs()).containsExactly("p_bag_001");
        assertThat(context.scope().catalogSpuIds()).containsExactly(101L, 102L);
        assertThat(context.needClarify()).isFalse();
    }

    @Test
    void multiTurnRefineResolvesCandidateReferenceFromSnapshot() {
        AgentSessionState sessionState = new AgentSessionState(
                "1.0",
                "user-1",
                "conversation-1",
                Instant.now(),
                List.of(),
                new RecommendationState(
                        "MULTI_TURN_REFINE",
                        null,
                        Map.of(),
                        Map.of(),
                        List.of(),
                        null,
                        new CandidateSnapshot(
                                List.of(),
                                List.of(
                                        new CandidateSnapshotItem(1, "p1", "sku-1", "ext-1", "商品1", null,
                                                new BigDecimal("120.00"), "p1.jpg", null, "上一轮第一个"),
                                        new CandidateSnapshotItem(2, "p2", "sku-2", "ext-2", "商品2", null,
                                                new BigDecimal("80.00"), "p2.jpg", null, "上一轮第二个")
                                ),
                                Instant.now()
                        ),
                        LastRecommendationResult.empty()
                ),
                new MultimodalState(
                        Map.of(
                                "inputModalities", List.of("text"),
                                "message", "第二个有更便宜的吗"
                        ),
                        List.of()
                ),
                CartState.empty(),
                OrderState.empty()
        );

        UnifiedQueryContext context = builder.build(sessionState);

        assertThat(context.positiveConstraints())
                .containsEntry("candidateRank", 2)
                .containsEntry("referenceProductId", "p2")
                .containsEntry("referenceExternalRef", "ext-2")
                .containsEntry("priceMax", new BigDecimal("80.00"));
        assertThat(context.scope().productIds()).contains("p1", "p2");
        assertThat(context.scope().externalRefs()).containsExactly("ext-2");
    }

    @Test
    void productCompareResolvesMultipleCandidateReferencesFromSnapshot() {
        AgentSessionState sessionState = new AgentSessionState(
                "1.0",
                "user-1",
                "conversation-1",
                Instant.now(),
                List.of(),
                new RecommendationState(
                        "PRODUCT_COMPARE",
                        null,
                        Map.of(),
                        Map.of(),
                        List.of(),
                        null,
                        new CandidateSnapshot(
                                List.of(),
                                List.of(
                                        new CandidateSnapshotItem(1, "p1", "sku-1", "ext-1", "商品1", null,
                                                new BigDecimal("120.00"), "p1.jpg", null, "上一轮第一个"),
                                        new CandidateSnapshotItem(2, "p2", "sku-2", "ext-2", "商品2", null,
                                                new BigDecimal("80.00"), "p2.jpg", null, "上一轮第二个")
                                ),
                                Instant.now()
                        ),
                        LastRecommendationResult.empty()
                ),
                new MultimodalState(
                        Map.of(
                                "inputModalities", List.of("text"),
                                "message", "对比第一个和第二个"
                        ),
                        List.of()
                ),
                CartState.empty(),
                OrderState.empty()
        );

        UnifiedQueryContext context = builder.build(sessionState);

        assertThat(context.positiveConstraints())
                .containsEntry("candidateRanks", List.of(1, 2))
                .containsEntry("compareProductIds", List.of("p1", "p2"));
        assertThat(context.scope().productIds()).contains("p1", "p2");
        assertThat(context.scope().externalRefs()).containsExactly("ext-1", "ext-2");
    }
}
