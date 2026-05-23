package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSkuView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.retrieval.spi.ProductSearchHit;
import com.bytedance.ai.retrieval.spi.ProductSearchRequest;
import com.bytedance.ai.retrieval.spi.ProductSearchSpi;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchProductsToolCallbackTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
    private final CapturingProductSearchSpi productSearchSpi = new CapturingProductSearchSpi();
    private final StubCatalogQueryFacade catalogQueryFacade = new StubCatalogQueryFacade();
    private final SearchProductsToolCallback callback = new SearchProductsToolCallback(
            productSearchSpi,
            catalogQueryFacade,
            jsonCodec
    );

    @Test
    void exposesSpringAiToolDefinitionAndHandledIntents() {
        assertThat(callback.getToolDefinition().name()).isEqualTo("search_products");
        assertThat(callback.getToolDefinition().inputSchema()).contains("\"query\"");
        assertThat(callback.handles())
                .containsExactlyInAnyOrder(IntentType.RECOMMEND_VAGUE, IntentType.FILTER_BY_ATTR, IntentType.REFINE);
    }

    @Test
    void callsProductSearchAndEnrichesCardsFromCatalog() {
        productSearchSpi.hits = List.of(new ProductSearchHit(
                9L,
                88L,
                "SPU-9",
                0.82d,
                "TITLE",
                "匹配轻便和防水",
                Map.of()
        ));
        catalogQueryFacade.spu = spu(9L, "SPU-9", "轻便双肩包");
        Slot slot = new Slot(
                List.of("轻便"),
                List.of(),
                new Slot.PriceRange(null, new BigDecimal("300")),
                "箱包",
                List.of("Acme"),
                null
        );

        String output = callback.call(jsonCodec.write(new SearchProductsToolCallback.SearchProductsInput(
                "推荐 300 元以下的双肩包",
                slot,
                5,
                List.of("TITLE")
        )));

        assertThat(productSearchSpi.lastRequest.query()).contains("推荐 300 元以下的双肩包", "轻便");
        assertThat(productSearchSpi.lastRequest.topK()).isEqualTo(5);
        assertThat(productSearchSpi.lastRequest.filter().sourceUriPrefix()).isEqualTo("catalog://spu/");
        assertThat(productSearchSpi.lastRequest.filter().headingPathContains()).isEqualTo("箱包");
        Map<String, Object> result = jsonCodec.readMap(output);
        assertThat(result.get("toolName")).isEqualTo("search_products");
        assertThat(output).contains("轻便双肩包", "https://img.example/9.png", "匹配轻便和防水", "#1", "priceRange");
    }

    @Test
    void fallsBackToExternalRefWhenCatalogPrimaryKeyIsMissing() {
        productSearchSpi.hits = List.of(new ProductSearchHit(
                null,
                99L,
                "SPU-EXT",
                0.6d,
                null,
                "片段",
                Map.of()
        ));
        catalogQueryFacade.spu = spu(10L, "SPU-EXT", "外部编号商品");

        String output = callback.call("{\"query\":\"找商品\"}");

        assertThat(output).contains("外部编号商品", "SPU-EXT");
        assertThat(catalogQueryFacade.findByExternalRefCalls).isEqualTo(1);
    }

    @Test
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> callback.call("{\"query\":\" \"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    private CatalogSpuView spu(Long id, String externalRef, String title) {
        return new CatalogSpuView(
                id,
                externalRef,
                title,
                "Acme",
                "箱包",
                new BigDecimal("199.00"),
                new BigDecimal("259.00"),
                12,
                "desc",
                List.of("https://img.example/" + id + ".png"),
                null,
                Map.of(),
                "DONE",
                "ACTIVE",
                1000L + id,
                List.<CatalogSkuView>of(),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private static class CapturingProductSearchSpi implements ProductSearchSpi {
        private ProductSearchRequest lastRequest;
        private List<ProductSearchHit> hits = new ArrayList<>();

        @Override
        public List<ProductSearchHit> search(ProductSearchRequest request) {
            lastRequest = request;
            return hits;
        }
    }

    private static class StubCatalogQueryFacade implements CatalogQueryFacade {
        private CatalogSpuView spu;
        private int findByExternalRefCalls;

        @Override
        public CatalogSpuView getSpu(Long spuId) {
            if (spu != null && spu.id().equals(spuId)) {
                return spu;
            }
            throw new IllegalArgumentException("missing spu");
        }

        @Override
        public Optional<CatalogSpuView> findSpuByExternalRef(String externalRef) {
            findByExternalRefCalls++;
            if (spu != null && spu.externalRef().equals(externalRef)) {
                return Optional.of(spu);
            }
            return Optional.empty();
        }

        @Override
        public List<CatalogSkuView> listSkus(Long spuId) {
            return List.of();
        }
    }
}
