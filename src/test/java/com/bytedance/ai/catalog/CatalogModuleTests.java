package com.bytedance.ai.catalog;

import com.bytedance.ai.catalog.api.CatalogAttributeExtractRequestedEvent;
import com.bytedance.ai.catalog.api.CatalogCommandFacade;
import com.bytedance.ai.catalog.api.CatalogImportRequest;
import com.bytedance.ai.catalog.api.CatalogImportSummary;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSpuCreateRequest;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.document.api.DocumentIndexRequestedEvent;
import com.bytedance.ai.indexing.api.IndexingCommandFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Catalog 模块端到端集成测试。
 *
 * <p>用全量 {@link SpringBootTest} 而非 {@code @ApplicationModuleTest}：
 * 因为属性抽取链路（worker → extractor）在构造期已经依赖 RagProperties，
 * 这条依赖通过 {@code @EnableConfigurationProperties} 注册在 RagConfiguration，
 * 完整启动上下文最直接地保证它存在。
 *
 * <p>{@code rag.catalog.enabled=false} 关掉 worker 真正调用 LLM，
 * 事件本身仍会通过同步 {@code @EventListener} 探针被捕获并断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(CatalogModuleTests.CatalogTestEventProbe.class)
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-modulith.sql",
        "rag.catalog.enabled=false"
})
class CatalogModuleTests {

    @Autowired
    private CatalogCommandFacade catalogCommandFacade;

    @Autowired
    private CatalogQueryFacade catalogQueryFacade;

    @Autowired
    private CatalogTestEventProbe eventProbe;

    /**
     * 绕开既有 indexing 模块在 H2 下不兼容的 ON CONFLICT 语法（pre-existing issue），
     * 把 IndexingCommandFacade 桩为 no-op，让 catalog 双写链路能完整跑完。
     */
    @MockitoBean
    private IndexingCommandFacade indexingCommandFacade;

    @Test
    void exposesCatalogFacades() {
        assertThat(catalogCommandFacade).isNotNull();
        assertThat(catalogQueryFacade).isNotNull();
    }

    @Test
    void importBatchDoubleWritesAndTriggersIndexingEvent() {
        eventProbe.clear();
        CatalogImportRequest request = new CatalogImportRequest(List.of(sample("SPU-M-1")));

        CatalogImportSummary summary = catalogCommandFacade.importBatch(request);

        assertThat(summary.failed()).isZero();
        assertThat(summary.succeeded()).isEqualTo(1);
        Long spuId = summary.succeededIds().get(0);

        CatalogSpuView view = catalogQueryFacade.getSpu(spuId);
        assertThat(view.documentId())
                .as("catalog_spu.document_id 必须回填为 rag_documents.id")
                .isNotNull();
        assertThat(view.attributesStatus()).isEqualTo("PENDING");
        assertThat(view.skus()).hasSize(1);

        await().untilAsserted(() -> {
            assertThat(eventProbe.indexEvents())
                    .as("应发布 DocumentIndexRequestedEvent")
                    .anyMatch(e -> e.documentId().equals(view.documentId())
                            && "document-command-service".equals(e.triggeredBy()));
            assertThat(eventProbe.attributeEvents())
                    .as("应发布 CatalogAttributeExtractRequestedEvent")
                    .anyMatch(e -> e.spuId().equals(spuId) && "import".equals(e.triggeredBy()));
        });
    }

    @Test
    void importBatchPartialSuccessReportsFailures() {
        CatalogSpuCreateRequest ok = sample("SPU-M-3-OK");
        CatalogSpuCreateRequest duplicate = sample("SPU-M-3-DUP");
        catalogCommandFacade.importBatch(new CatalogImportRequest(List.of(duplicate)));
        CatalogImportSummary summary = catalogCommandFacade.importBatch(
                new CatalogImportRequest(List.of(ok, duplicate))
        );

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failures()).hasSize(1);
        assertThat(summary.failures().get(0).externalRef()).isEqualTo("SPU-M-3-DUP");
    }

    private CatalogSpuCreateRequest sample(String externalRef) {
        return new CatalogSpuCreateRequest(
                externalRef,
                "测试商品 " + externalRef,
                "TestBrand",
                "类目/子类目",
                new BigDecimal("99"),
                new BigDecimal("99"),
                10,
                "这是一段足够长的商品描述，用于在 RAG 内容字段上产生稳定的 sha256。",
                List.of("https://example.com/img.jpg"),
                null,
                List.of(new CatalogSpuCreateRequest.SkuDraft(
                        "SKU-" + externalRef,
                        Map.of("color", "黑色"),
                        new BigDecimal("99"),
                        10
                ))
        );
    }

    /**
     * 用同步 {@link EventListener} 捕获两类事件，避免对 Modulith Scenario 的依赖。
     * 事件由 catalog/document 模块同步发布在事务边界附近，单测里完全可用。
     */
    @Component
    static class CatalogTestEventProbe {
        private final List<DocumentIndexRequestedEvent> indexEvents = new CopyOnWriteArrayList<>();
        private final List<CatalogAttributeExtractRequestedEvent> attributeEvents = new CopyOnWriteArrayList<>();

        @EventListener
        void onIndex(DocumentIndexRequestedEvent event) {
            indexEvents.add(event);
        }

        @EventListener
        void onAttribute(CatalogAttributeExtractRequestedEvent event) {
            attributeEvents.add(event);
        }

        List<DocumentIndexRequestedEvent> indexEvents() {
            return List.copyOf(indexEvents);
        }

        List<CatalogAttributeExtractRequestedEvent> attributeEvents() {
            return List.copyOf(attributeEvents);
        }

        void clear() {
            indexEvents.clear();
            attributeEvents.clear();
        }
    }
}
