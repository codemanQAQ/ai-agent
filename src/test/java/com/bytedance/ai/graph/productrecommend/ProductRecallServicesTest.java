package com.bytedance.ai.graph.productrecommend;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import com.bytedance.ai.graph.session.CandidateSnapshot;
import com.bytedance.ai.graph.session.CandidateSnapshotItem;
import com.bytedance.ai.graph.session.UnifiedQueryContext;
import com.bytedance.ai.graph.session.UnifiedQueryScope;
import com.bytedance.ai.indexing.api.IndexingChunkQueryFacade;
import com.bytedance.ai.indexing.api.RagChunkSearchView;
import com.bytedance.ai.shared.metadata.RagChunkMetadataHelper;
import com.bytedance.ai.shared.metadata.RagChunkType;
import com.bytedance.ai.shared.metadata.RagSearchFilter;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRecallServicesTest {

    @Test
    void catalogKeywordRecallReturnsProductCandidates() {
        StubCatalogQueryFacade catalog = new StubCatalogQueryFacade(List.of(cleanser()));
        CategorySynonymRegistry synonymRegistry = new CategorySynonymRegistry();
        synonymRegistry.load();
        ProductRecallService service = new CatalogKeywordProductRecallService(catalog, synonymRegistry);

        List<ProductRecallCandidate> candidates = service.recall(new ProductRecallRequest(context(
                "推荐适合油皮的洗面奶",
                Map.of(),
                Map.of(),
                CandidateSnapshot.empty()
        ), 3));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().source()).isEqualTo(ProductRecallSource.CATALOG_KEYWORD);
        assertThat(candidates.getFirst().productId()).isEqualTo("p_beauty_001");
        assertThat(candidates.getFirst().evidence().getFirst().evidenceType()).isEqualTo("catalog_keyword");
    }

    @Test
    void catalogStructuredFilterRecallAppliesBrandCategoryPriceAndSpecs() {
        StubCatalogQueryFacade catalog = new StubCatalogQueryFacade(List.of(cleanser(), camera()));
        ProductRecallService service = new CatalogStructuredFilterProductRecallService(catalog);

        List<ProductRecallCandidate> candidates = service.recall(new ProductRecallRequest(context(
                "找洗面奶",
                Map.of(
                        "category", "洁面",
                        "brand", "清透",
                        "priceMax", 100,
                        "inStock", true,
                        "skuSpec", Map.of("容量", "150ml")
                ),
                Map.of(),
                CandidateSnapshot.empty()
        ), 3));

        assertThat(candidates).hasSize(1);
        ProductRecallCandidate candidate = candidates.getFirst();
        assertThat(candidate.source()).isEqualTo(ProductRecallSource.CATALOG_FILTER);
        assertThat(candidate.matchedSlots())
                .containsEntry("brand", "清透")
                .containsEntry("category", "洁面")
                .containsEntry("inStock", true);
    }

    @Test
    void ragChunkRecallBackfillsSpuByExternalRef() {
        StubCatalogQueryFacade catalog = new StubCatalogQueryFacade(List.of(cleanser()));
        StubIndexingChunkQueryFacade chunks = new StubIndexingChunkQueryFacade(List.of(new RagChunkSearchView(
                11L,
                22L,
                "清透洁面 FAQ",
                "catalog-spu",
                "catalog-spu://p_beauty_001",
                "p_beauty_001",
                1L,
                3,
                "适合油皮日常清洁。",
                "vec-1",
                Map.of("chunkType", "OFFICIAL_FAQ", "externalRef", "p_beauty_001", "headingPath", List.of("FAQ"))
        )));
        ProductRecallService service = new RagChunkProductRecallService(
                chunks,
                catalog,
                new RagChunkMetadataHelper(new RagJsonCodec(JsonMapper.builder().build()))
        );

        List<ProductRecallCandidate> candidates = service.recall(new ProductRecallRequest(context(
                "油皮 洗面奶",
                Map.of(),
                Map.of(),
                CandidateSnapshot.empty()
        ), 3));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().source()).isEqualTo(ProductRecallSource.RAG_CHUNK);
        assertThat(candidates.getFirst().evidence().getFirst().evidenceType()).isEqualTo("official_faq");
        assertThat(chunks.lastFilter.externalRefs()).isEmpty();
    }

    @Test
    void detailFaqReviewRecallPrioritizesProductKnowledgeChunkTypes() {
        StubCatalogQueryFacade catalog = new StubCatalogQueryFacade(List.of(cleanser()));
        StubIndexingChunkQueryFacade chunks = new StubIndexingChunkQueryFacade(List.of(
                new RagChunkSearchView(
                        11L,
                        21L,
                        "清透洁面 参数",
                        "catalog-spu",
                        "catalog-spu://p_beauty_001",
                        "p_beauty_001",
                        1L,
                        1,
                        "容量 150ml。",
                        "vec-desc",
                        Map.of("chunkType", "ATTR", "externalRef", "p_beauty_001")
                ),
                new RagChunkSearchView(
                        11L,
                        22L,
                        "清透洁面 FAQ",
                        "catalog-spu",
                        "catalog-spu://p_beauty_001",
                        "p_beauty_001",
                        1L,
                        3,
                        "适合油皮日常清洁。",
                        "vec-faq",
                        Map.of("chunkType", "OFFICIAL_FAQ", "externalRef", "p_beauty_001")
                )
        ));
        ProductRecallService service = new RagChunkProductRecallService(
                chunks,
                catalog,
                new RagChunkMetadataHelper(new RagJsonCodec(JsonMapper.builder().build()))
        );
        ProductRecallPlan plan = new ProductRecommendStrategyPlanner().plan(ProductRecommendSubScene.DETAIL_FAQ_REVIEW_ANSWER);

        List<ProductRecallCandidate> candidates = service.recall(new ProductRecallRequest(context(
                "这款适合油皮吗",
                Map.of(),
                Map.of(),
                CandidateSnapshot.empty()
        ), 3, plan));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().evidence().getFirst().evidenceType()).isEqualTo("official_faq");
        assertThat(chunks.filters.getFirst().chunkTypes())
                .containsExactly(
                        RagChunkType.OFFICIAL_FAQ,
                        RagChunkType.USER_REVIEW,
                        RagChunkType.REVIEW_SUMMARY,
                        RagChunkType.MARKETING_DESCRIPTION
                );
    }

    @Test
    void historyRecallReturnsSnapshotItems() {
        ProductRecallService service = new HistoryPreferenceProductRecallService();
        CandidateSnapshot snapshot = new CandidateSnapshot(
                List.of(),
                List.of(new CandidateSnapshotItem(
                        2,
                        "p_beauty_001",
                        "sku-1",
                        "p_beauty_001",
                        "清透洁面",
                        "150ml",
                        new BigDecimal("89.00"),
                        "image.jpg",
                        ProductRecallSource.CATALOG_KEYWORD,
                        "上一轮推荐"
                )),
                Instant.now()
        );

        List<ProductRecallCandidate> candidates = service.recall(new ProductRecallRequest(context(
                "第二个",
                Map.of(),
                Map.of(),
                snapshot
        ), 3));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().source()).isEqualTo(ProductRecallSource.HISTORY_SNAPSHOT);
        assertThat(candidates.getFirst().matchedSlots()).containsEntry("snapshotRank", 2);
    }

    @Test
    void imageVectorRecallDelegatesToRegisteredPort() {
        ProductRecallService service = new ImageVectorProductRecallService(List.of((queryContext, limit) -> List.of(
                new ProductRecallCandidate(
                        "p_image_001",
                        null,
                        null,
                        "p_image_001",
                        "图片相似商品",
                        null,
                        List.of(),
                        null,
                        null,
                        "image.jpg",
                        ProductRecallSource.IMAGE_VECTOR,
                        0.9d,
                        0.9d,
                        Map.of("imageEmbeddingRef", queryContext.imageEmbeddingRef()),
                        List.of()
                )
        )));

        UnifiedQueryContext context = new UnifiedQueryContext(
                "1.0",
                "PHOTO_SEARCH",
                "找相似款",
                List.of("image"),
                "input.jpg",
                "一张洁面产品图片",
                "image-vec-1",
                Map.of(),
                Map.of(),
                UnifiedQueryScope.empty(),
                CandidateSnapshot.empty(),
                false,
                List.of(),
                null
        );

        List<ProductRecallCandidate> candidates = service.recall(new ProductRecallRequest(context, 3));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().source()).isEqualTo(ProductRecallSource.IMAGE_VECTOR);
        assertThat(candidates.getFirst().matchedSlots()).containsEntry("imageEmbeddingRef", "image-vec-1");
    }

    @Test
    void pythonFaissImageVectorAdapterMapsMainRecallOutput() {
        RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
        CapturingPythonRunner runner = new CapturingPythonRunner("""
                {
                  "topK": 1,
                  "resultCount": 1,
                  "products": [
                    {
                      "productId": "p_beauty_001",
                      "spuId": "1",
                      "skuId": "sku-1",
                      "externalRef": "p_beauty_001",
                      "title": "清透氨基酸洗面奶",
                      "brand": "清透",
                      "categoryPath": ["美妆护肤", "洁面"],
                      "price": 89.0,
                      "stock": 12,
                      "imageUrl": "images/p_beauty_001_live.jpg",
                      "rawScore": 0.92,
                      "rankScore": 0.92,
                      "matchedSlots": {
                        "fusionChannels": ["image"],
                        "channelScores": {"image": {"score": 0.92}}
                      },
                      "evidence": [
                        {
                          "evidenceType": "image_vector",
                          "title": "p_beauty_001#image",
                          "content": "商品主图视觉向量",
                          "chunkId": "p_beauty_001::image::0",
                          "parentChunkId": "p_beauty_001::profile",
                          "productId": "p_beauty_001",
                          "metadata": {
                            "faissId": 7,
                            "chunkType": "image_embedding",
                            "embeddingModality": "image"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);
        PythonFaissImageVectorRecallAdapter adapter = new PythonFaissImageVectorRecallAdapter(
                jsonCodec,
                runner,
                new PythonFaissImageVectorRecallAdapter.AdapterOptions(
                        "python",
                        Path.of("src/main/python"),
                        Duration.ofSeconds(5),
                        200
                )
        );
        UnifiedQueryContext context = new UnifiedQueryContext(
                "1.0",
                "PHOTO_SEARCH",
                "找相似洁面",
                List.of("image"),
                "images/p_beauty_001_live.jpg",
                "一张洁面产品图片",
                "[0.1,0.2,0.3]",
                Map.of(),
                Map.of(),
                new UnifiedQueryScope(List.of("p_beauty_001"), List.of("p_beauty_001"), List.of(1L)),
                CandidateSnapshot.empty(),
                false,
                List.of(),
                null
        );

        List<ProductRecallCandidate> candidates = adapter.recallByImage(context, 1);

        assertThat(candidates).hasSize(1);
        ProductRecallCandidate candidate = candidates.getFirst();
        assertThat(candidate.source()).isEqualTo(ProductRecallSource.IMAGE_VECTOR);
        assertThat(candidate.productId()).isEqualTo("p_beauty_001");
        assertThat(candidate.categoryPath()).containsExactly("美妆护肤", "洁面");
        assertThat(candidate.evidence().getFirst().chunkId()).isEqualTo("p_beauty_001::image::0");
        assertThat(runner.requestPayload)
                .containsEntry("topK", 1)
                .containsEntry("queryText", "找相似洁面")
                .containsEntry("imageEmbeddingRef", "[0.1,0.2,0.3]");
        assertThat(runner.requestPayload.get("catalogSpuIds")).isEqualTo(List.of(1L));
    }

    @Test
    void multiRecallAggregatesAllRegisteredSources() {
        ProductRecallService first = fixedService(ProductRecallSource.CATALOG_KEYWORD, "p1");
        ProductRecallService second = fixedService(ProductRecallSource.RAG_CHUNK, "p2");
        ProductMultiRecallService multiRecallService = new ProductMultiRecallService(List.of(first, second));

        List<ProductRecallCandidate> candidates = multiRecallService.recall(context(
                "洗面奶",
                Map.of(),
                Map.of(),
                CandidateSnapshot.empty()
        ), 3);

        assertThat(candidates)
                .extracting(ProductRecallCandidate::productId)
                .containsExactly("p1", "p2");
    }

    @Test
    void fuzzyRecommendPlanUsesWideRecallWithoutFilterOrImageSources() {
        ProductRecommendStrategyPlanner planner = new ProductRecommendStrategyPlanner();

        ProductRecallPlan plan = planner.plan(ProductRecommendSubScene.FUZZY_RECOMMEND);

        assertThat(plan.enabledSources())
                .containsExactly(
                        ProductRecallSource.CATALOG_KEYWORD,
                        ProductRecallSource.RAG_CHUNK,
                        ProductRecallSource.HISTORY_SNAPSHOT,
                        ProductRecallSource.PREFERENCE
                )
                .doesNotContain(ProductRecallSource.CATALOG_FILTER, ProductRecallSource.IMAGE_VECTOR);
        assertThat(plan.outputLimit()).isEqualTo(5);
    }

    @Test
    void conditionFilterPlanPrioritizesStructuredFilterAndEnforcesPositiveConstraints() {
        ProductRecommendStrategyPlanner planner = new ProductRecommendStrategyPlanner();

        ProductRecallPlan plan = planner.plan(ProductRecommendSubScene.CONDITION_FILTER);

        assertThat(plan.enabledSources())
                .containsExactly(
                        ProductRecallSource.CATALOG_FILTER,
                        ProductRecallSource.RAG_CHUNK,
                        ProductRecallSource.CATALOG_KEYWORD,
                        ProductRecallSource.HISTORY_SNAPSHOT
                );
        assertThat(plan.enforcePositiveConstraints()).isTrue();
        assertThat(plan.perSourceLimit()).isEqualTo(12);
    }

    @Test
    void negativeConstraintPlanKeepsWideRecallAndDoesNotEnforcePositiveConstraints() {
        ProductRecommendStrategyPlanner planner = new ProductRecommendStrategyPlanner();

        ProductRecallPlan plan = planner.plan(ProductRecommendSubScene.NEGATIVE_CONSTRAINT);

        assertThat(plan.enabledSources())
                .containsExactly(
                        ProductRecallSource.HISTORY_SNAPSHOT,
                        ProductRecallSource.CATALOG_KEYWORD,
                        ProductRecallSource.RAG_CHUNK,
                        ProductRecallSource.CATALOG_FILTER
                );
        assertThat(plan.enforcePositiveConstraints()).isFalse();
        assertThat(plan.outputLimit()).isEqualTo(5);
    }

    @Test
    void sceneBundlePlannerSplitsScenarioIntoExecutableRoles() {
        SceneBundlePlanner planner = new SceneBundlePlanner();

        SceneBundlePlan plan = planner.plan(context(
                "给我一套露营用的组合",
                Map.of("scenario", "露营", "audience", "新手"),
                Map.of(),
                CandidateSnapshot.empty()
        ));

        assertThat(plan.scenario()).isEqualTo("露营/户外");
        assertThat(plan.audience()).isEqualTo("新手");
        assertThat(plan.roles())
                .extracting(SceneBundleRole::name)
                .containsExactly("照明", "收纳", "防晒", "补水");
        assertThat(plan.roles().getFirst().constraints())
                .containsEntry("bundleRole", "照明")
                .containsEntry("category", "数码电子");
    }

    @Test
    void sceneBundleRecommendPlanUsesCrossCategoryWideRecall() {
        ProductRecommendStrategyPlanner planner = new ProductRecommendStrategyPlanner();

        ProductRecallPlan plan = planner.plan(ProductRecommendSubScene.SCENE_BUNDLE_RECOMMEND);

        assertThat(plan.enabledSources())
                .containsExactly(
                        ProductRecallSource.CATALOG_KEYWORD,
                        ProductRecallSource.RAG_CHUNK,
                        ProductRecallSource.CATALOG_FILTER,
                        ProductRecallSource.HISTORY_SNAPSHOT
                );
        assertThat(plan.enforcePositiveConstraints()).isFalse();
        assertThat(plan.outputLimit()).isEqualTo(6);
    }

    @Test
    void multiRecallRunsOnlySourcesEnabledByPlan() {
        ProductRecallService keyword = fixedService(ProductRecallSource.CATALOG_KEYWORD, "p-keyword");
        ProductRecallService rag = fixedService(ProductRecallSource.RAG_CHUNK, "p-rag");
        ProductRecallService filter = fixedService(ProductRecallSource.CATALOG_FILTER, "p-filter");
        ProductRecallService image = fixedService(ProductRecallSource.IMAGE_VECTOR, "p-image");
        ProductMultiRecallService multiRecallService = new ProductMultiRecallService(List.of(keyword, rag, filter, image));
        ProductRecallPlan fuzzyPlan = new ProductRecommendStrategyPlanner().plan(ProductRecommendSubScene.FUZZY_RECOMMEND);

        List<ProductRecallCandidate> candidates = multiRecallService.recall(context(
                "推荐洗面奶",
                Map.of(),
                Map.of(),
                CandidateSnapshot.empty()
        ), fuzzyPlan);

        assertThat(candidates)
                .extracting(ProductRecallCandidate::productId)
                .containsExactly("p-keyword", "p-rag");
    }

    private ProductRecallService fixedService(ProductRecallSource source, String productId) {
        return new ProductRecallService() {
            @Override
            public ProductRecallSource source() {
                return source;
            }

            @Override
            public List<ProductRecallCandidate> recall(ProductRecallRequest request) {
                return List.of(new ProductRecallCandidate(
                        productId,
                        null,
                        null,
                        productId,
                        productId,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        source,
                        1.0d,
                        1.0d,
                        Map.of(),
                        List.of()
                ));
            }
        };
    }

    private UnifiedQueryContext context(
            String queryText,
            Map<String, Object> positive,
            Map<String, Object> negative,
            CandidateSnapshot snapshot
    ) {
        return new UnifiedQueryContext(
                "1.0",
                "FUZZY_RECOMMEND",
                queryText,
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

    private CatalogSpuView cleanser() {
        return new CatalogSpuView(
                1L,
                "p_beauty_001",
                "清透氨基酸洗面奶",
                "清透",
                "美妆护肤/洁面",
                new BigDecimal("89.00"),
                new BigDecimal("89.00"),
                12,
                "适合油皮洁面。",
                List.of("cleanser.jpg"),
                null,
                Map.of(),
                "DONE",
                "ACTIVE",
                101L,
                List.of(new CatalogSkuView(1L, "sku-1", Map.of("容量", "150ml"), new BigDecimal("89.00"), 12, "ACTIVE")),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private CatalogSpuView camera() {
        return new CatalogSpuView(
                2L,
                "p_digital_001",
                "便携相机",
                "影像",
                "数码电子/相机",
                new BigDecimal("2999.00"),
                new BigDecimal("2999.00"),
                5,
                "便携拍摄。",
                List.of("camera.jpg"),
                null,
                Map.of(),
                "DONE",
                "ACTIVE",
                102L,
                List.of(),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private static final class StubCatalogQueryFacade implements CatalogQueryFacade {

        private final List<CatalogSpuView> spus;

        private StubCatalogQueryFacade(List<CatalogSpuView> spus) {
            this.spus = spus;
        }

        @Override
        public CatalogSpuView getSpu(Long spuId) {
            return spus.stream()
                    .filter(spu -> spu.id().equals(spuId))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public Optional<CatalogSpuView> findSpuByExternalRef(String externalRef) {
            return spus.stream()
                    .filter(spu -> spu.externalRef().equals(externalRef))
                    .findFirst();
        }

        @Override
        public List<CatalogSpuView> searchActiveSpus(String keyword, int limit) {
            return spus.stream()
                    .filter(spu -> contains(spu.title(), keyword) || contains(spu.brand(), keyword) || contains(spu.categoryPath(), keyword))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<CatalogSkuView> listSkus(Long spuId) {
            return getSpu(spuId).skus();
        }

        private boolean contains(String actual, String keyword) {
            return actual != null && keyword != null && keyword.chars()
                    .mapToObj(item -> String.valueOf((char) item))
                    .anyMatch(actual::contains);
        }
    }

    private static final class StubIndexingChunkQueryFacade implements IndexingChunkQueryFacade {

        private final List<RagChunkSearchView> chunks;
        private RagSearchFilter lastFilter;
        private final List<RagSearchFilter> filters = new java.util.ArrayList<>();

        private StubIndexingChunkQueryFacade(List<RagChunkSearchView> chunks) {
            this.chunks = chunks;
        }

        @Override
        public List<RagChunkSearchView> findKeywordCandidates(Set<String> tokens, int limit) {
            return chunks.stream().limit(limit).toList();
        }

        @Override
        public List<RagChunkSearchView> findKeywordCandidates(Set<String> tokens, int limit, RagSearchFilter filter) {
            this.lastFilter = filter;
            this.filters.add(filter);
            return chunks.stream()
                    .filter(chunk -> matchesChunkTypes(chunk, filter))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<RagChunkSearchView> findActiveChunksByDocumentIdAndRange(Long documentId, int startChunkIndex, int endChunkIndex) {
            return List.of();
        }

        @Override
        public List<RagChunkSearchView> findSearchableByVectorIds(List<String> vectorIds) {
            return List.of();
        }

        private boolean matchesChunkTypes(RagChunkSearchView chunk, RagSearchFilter filter) {
            if (filter == null || filter.chunkTypes().isEmpty()) {
                return true;
            }
            Object rawType = chunk.metadata() instanceof Map<?, ?> metadata ? metadata.get("chunkType") : null;
            RagChunkType chunkType = RagChunkType.parseOrBody(rawType == null ? null : String.valueOf(rawType));
            return filter.chunkTypes().contains(chunkType);
        }
    }

    private static final class CapturingPythonRunner implements PythonFaissImageVectorRecallAdapter.PythonProcessRunner {

        private final String stdout;

        private Map<String, Object> requestPayload = new LinkedHashMap<>();

        private CapturingPythonRunner(String stdout) {
            this.stdout = stdout;
        }

        @Override
        public PythonFaissImageVectorRecallAdapter.ProcessResult run(
                List<String> commandTemplate,
                Map<String, String> environment,
                Map<String, Object> requestPayload,
                Duration timeout
        ) {
            this.requestPayload = requestPayload;
            return new PythonFaissImageVectorRecallAdapter.ProcessResult(0, stdout, "");
        }
    }
}
