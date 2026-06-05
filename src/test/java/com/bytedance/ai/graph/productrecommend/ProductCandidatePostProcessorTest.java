package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.session.CandidateSnapshot;
import com.bytedance.ai.graph.session.CandidateSnapshotItem;
import com.bytedance.ai.graph.session.UnifiedQueryContext;
import com.bytedance.ai.graph.session.UnifiedQueryScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductCandidatePostProcessorTest {

    private final RrfFusionService fusionService = new RrfFusionService();
    private final NegativeConstraintFilter negativeConstraintFilter = new NegativeConstraintFilter();
    private final LightweightProductRanker ranker = new LightweightProductRanker();
    private final CandidateSnapshotMapper snapshotMapper = new CandidateSnapshotMapper();
    private final ProductCandidatePostProcessor postProcessor = new ProductCandidatePostProcessor(
            fusionService,
            negativeConstraintFilter,
            ranker,
            snapshotMapper
    );

    @Test
    void rrfFusionDeduplicatesProductAndKeepsEvidenceFromMultipleSources() {
        List<ProductRecallCandidate> fused = fusionService.fuse(List.of(
                candidate("p1", ProductRecallSource.CATALOG_KEYWORD, 0.9d, 8, "洁面", "品牌A"),
                candidate("p1", ProductRecallSource.RAG_CHUNK, 0.7d, 8, "FAQ 命中", "品牌A"),
                candidate("p2", ProductRecallSource.CATALOG_KEYWORD, 0.8d, 8, "洁面", "品牌B")
        ));

        assertThat(fused).hasSize(2);
        ProductRecallCandidate p1 = fused.stream()
                .filter(candidate -> "p1".equals(candidate.productId()))
                .findFirst()
                .orElseThrow();
        assertThat(p1.evidence())
                .extracting(ProductRecallEvidence::source)
                .contains(ProductRecallSource.CATALOG_KEYWORD, ProductRecallSource.RAG_CHUNK);
        assertThat(p1.rankScore()).isGreaterThan(0);
    }

    @Test
    void negativeConstraintFilterRemovesExcludedBrandAndKeepsReason() {
        ProductCandidateFilterResult result = negativeConstraintFilter.filter(
                List.of(
                        candidate("p1", ProductRecallSource.CATALOG_KEYWORD, 0.9d, 8, "洁面", "品牌A"),
                        candidate("p2", ProductRecallSource.CATALOG_KEYWORD, 0.8d, 8, "洁面", "品牌B")
                ),
                Map.of("brand", List.of("品牌A"))
        );

        assertThat(result.candidates()).extracting(ProductRecallCandidate::productId).containsExactly("p2");
        assertThat(result.exclusions()).hasSize(1);
        assertThat(result.exclusions().getFirst().reason()).isEqualTo("命中排除品牌");
    }

    @Test
    void negativeConstraintFilterRemovesPriceAndEvidenceKeywordMatches() {
        ProductCandidateFilterResult result = negativeConstraintFilter.filter(
                List.of(
                        candidate("p1", ProductRecallSource.RAG_CHUNK, 0.9d, 8, "用户评价：香精味明显", "品牌A", new BigDecimal("89.00"), Map.of()),
                        candidate("p2", ProductRecallSource.CATALOG_KEYWORD, 0.8d, 8, "温和洁面", "品牌B", new BigDecimal("129.00"), Map.of()),
                        candidate("p3", ProductRecallSource.CATALOG_KEYWORD, 0.7d, 8, "温和洁面", "品牌C", new BigDecimal("79.00"), Map.of())
                ),
                Map.of(
                        "price", Map.of("max", 100),
                        "reviewSignals", List.of("香精味")
                )
        );

        assertThat(result.candidates()).extracting(ProductRecallCandidate::productId).containsExactly("p3");
        assertThat(result.exclusions())
                .extracting(ProductCandidateExclusion::reason)
                .containsExactlyInAnyOrder("命中排除关键词", "超过负向价格上限");
    }

    @Test
    void lightweightRankerUsesConstraintAndStockSignals() {
        List<ProductRecallCandidate> ranked = ranker.rank(
                List.of(
                        candidate("p1", ProductRecallSource.CATALOG_KEYWORD, 0.8d, 0, "洁面", "品牌A"),
                        candidate("p2", ProductRecallSource.CATALOG_FILTER, 0.75d, 20, "洁面", "品牌B", Map.of("category", "洁面"))
                ),
                Map.of("priceMax", 100),
                Map.of()
        );

        assertThat(ranked).extracting(ProductRecallCandidate::productId).containsExactly("p2", "p1");
        assertThat(ranked.getFirst().rankScore()).isGreaterThan(ranked.get(1).rankScore());
    }

    @Test
    void postProcessorFusesFiltersRanksAndBuildsCandidateSnapshot() {
        ProductCandidatePostProcessResult result = postProcessor.process(
                List.of(
                        candidate("p1", ProductRecallSource.CATALOG_KEYWORD, 0.9d, 10, "洁面", "品牌A"),
                        candidate("p1", ProductRecallSource.RAG_CHUNK, 0.7d, 10, "FAQ 命中", "品牌A"),
                        candidate("p2", ProductRecallSource.CATALOG_FILTER, 0.85d, 5, "磨砂洁面", "品牌B")
                ),
                context(Map.of("priceMax", 100), Map.of("keywords", List.of("磨砂"))),
                3
        );

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().productId()).isEqualTo("p1");
        assertThat(result.exclusions()).extracting(ProductCandidateExclusion::productId).containsExactly("p2");
        assertThat(result.candidateSnapshot().items()).hasSize(1);
        assertThat(result.candidateSnapshot().items().getFirst().rank()).isEqualTo(1);
        assertThat(result.candidateSnapshot().productIds()).containsExactly("p1");
    }

    @Test
    void conditionFilterPlanEnforcesPositiveConstraintsAfterFusion() {
        ProductCandidatePostProcessResult result = postProcessor.process(
                List.of(
                        candidate("p1", ProductRecallSource.CATALOG_FILTER, 0.9d, 10, "洁面", "品牌A", Map.of("category", "洁面")),
                        candidate("p2", ProductRecallSource.RAG_CHUNK, 0.95d, 10, "洁面", "品牌B"),
                        candidate("p3", ProductRecallSource.CATALOG_KEYWORD, 0.88d, 10, "洁面", "品牌A", new BigDecimal("129.00"), Map.of()),
                        candidate("p4", ProductRecallSource.CATALOG_KEYWORD, 0.86d, 0, "洁面", "品牌A")
                ),
                context(Map.of("brand", "品牌A", "category", "洁面", "priceMax", 100, "inStock", true), Map.of()),
                new ProductRecommendStrategyPlanner().plan(ProductRecommendSubScene.CONDITION_FILTER),
                5
        );

        assertThat(result.candidates())
                .extracting(ProductRecallCandidate::productId)
                .containsExactly("p1");
        assertThat(result.exclusions())
                .extracting(ProductCandidateExclusion::reason)
                .contains("不满足品牌条件", "不满足价格条件", "不满足库存条件");
    }

    @Test
    void multiTurnRefineBoostsSnapshotCandidatesButAllowsSupplementCandidates() {
        ProductCandidatePostProcessResult result = postProcessor.process(
                List.of(
                        candidate("p1", ProductRecallSource.HISTORY_SNAPSHOT, 0.4d, 10, "上一轮候选", "品牌A"),
                        candidate("p2", ProductRecallSource.CATALOG_FILTER, 0.95d, 10, "新增候选", "品牌A")
                ),
                context(
                        Map.of("category", "洁面"),
                        Map.of(),
                        new CandidateSnapshot(
                                List.of(),
                                List.of(new CandidateSnapshotItem(
                                        1,
                                        "p1",
                                        "sku-p1",
                                        "p1",
                                        "商品p1",
                                        null,
                                        new BigDecimal("89.00"),
                                        "p1.jpg",
                                        ProductRecallSource.CATALOG_KEYWORD,
                                        "上一轮推荐"
                                )),
                                Instant.now()
                        )
                ),
                new ProductRecommendStrategyPlanner().plan(ProductRecommendSubScene.MULTI_TURN_REFINE),
                5
        );

        assertThat(result.candidates())
                .extracting(ProductRecallCandidate::productId)
                .containsExactly("p1", "p2");
        assertThat(result.candidateSnapshot().productIds()).containsExactly("p1", "p2");
    }

    @Test
    void multiTurnExplicitCandidateReferenceNarrowsToReferencedProduct() {
        ProductCandidatePostProcessResult result = postProcessor.process(
                List.of(
                        candidate("p1", ProductRecallSource.HISTORY_SNAPSHOT, 0.9d, 10, "上一轮第一个", "品牌A"),
                        candidate("p2", ProductRecallSource.HISTORY_SNAPSHOT, 0.8d, 10, "上一轮第二个", "品牌A")
                ),
                context(
                        Map.of("productIds", List.of("p2"), "priceMax", 100),
                        Map.of(),
                        new CandidateSnapshot(List.of("p1", "p2"), Instant.now())
                ),
                new ProductRecommendStrategyPlanner().plan(ProductRecommendSubScene.MULTI_TURN_REFINE),
                5
        );

        assertThat(result.candidates())
                .extracting(ProductRecallCandidate::productId)
                .containsExactly("p2");
        assertThat(result.exclusions())
                .extracting(ProductCandidateExclusion::reason)
                .containsExactly("不满足商品范围条件");
    }

    @Test
    void negativeConstraintPlanFiltersAfterFusionAndKeepsExclusionReasons() {
        ProductCandidatePostProcessResult result = postProcessor.process(
                List.of(
                        candidate("p1", ProductRecallSource.CATALOG_KEYWORD, 0.9d, 10, "洁面", "品牌A"),
                        candidate("p2", ProductRecallSource.RAG_CHUNK, 0.8d, 10, "FAQ：含酒精", "品牌B"),
                        candidate("p3", ProductRecallSource.CATALOG_KEYWORD, 0.7d, 10, "洁面", "品牌C")
                ),
                context(Map.of("category", "洁面"), Map.of("brands", List.of("品牌A"), "ingredients", List.of("酒精"))),
                new ProductRecommendStrategyPlanner().plan(ProductRecommendSubScene.NEGATIVE_CONSTRAINT),
                5
        );

        assertThat(result.candidates()).extracting(ProductRecallCandidate::productId).containsExactly("p3");
        assertThat(result.exclusions())
                .extracting(ProductCandidateExclusion::reason)
                .containsExactlyInAnyOrder("命中排除品牌", "命中排除关键词");
    }

    private UnifiedQueryContext context(Map<String, Object> positive, Map<String, Object> negative) {
        return context(positive, negative, CandidateSnapshot.empty());
    }

    private UnifiedQueryContext context(
            Map<String, Object> positive,
            Map<String, Object> negative,
            CandidateSnapshot snapshot
    ) {
        return new UnifiedQueryContext(
                "1.0",
                "FUZZY_RECOMMEND",
                "推荐洗面奶",
                List.of("text"),
                null,
                null,
                null,
                positive,
                negative,
                UnifiedQueryScope.empty(),
                snapshot,
                false,
                List.of(),
                null
        );
    }

    private ProductRecallCandidate candidate(
            String productId,
            ProductRecallSource source,
            double rawScore,
            int stock,
            String evidenceText,
            String brand
    ) {
        return candidate(productId, source, rawScore, stock, evidenceText, brand, Map.of());
    }

    private ProductRecallCandidate candidate(
            String productId,
            ProductRecallSource source,
            double rawScore,
            int stock,
            String evidenceText,
            String brand,
            Map<String, Object> matchedSlots
    ) {
        return candidate(productId, source, rawScore, stock, evidenceText, brand, new BigDecimal("89.00"), matchedSlots);
    }

    private ProductRecallCandidate candidate(
            String productId,
            ProductRecallSource source,
            double rawScore,
            int stock,
            String evidenceText,
            String brand,
            BigDecimal price,
            Map<String, Object> matchedSlots
    ) {
        return new ProductRecallCandidate(
                productId,
                "spu-" + productId,
                "sku-" + productId,
                productId,
                "商品" + productId,
                brand,
                List.of("美妆护肤", "洁面"),
                price,
                stock,
                productId + ".jpg",
                source,
                rawScore,
                rawScore,
                matchedSlots,
                List.of(new ProductRecallEvidence(
                        source,
                        source.name().toLowerCase(),
                        "证据" + productId,
                        evidenceText,
                        null,
                        null,
                        productId,
                        Map.of("source", source.name())
                ))
        );
    }
}
