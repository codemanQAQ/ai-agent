package com.bytedance.ai.catalog;

import com.bytedance.ai.catalog.api.CatalogCommandFacade;
import com.bytedance.ai.catalog.api.CatalogImportRequest;
import com.bytedance.ai.catalog.api.CatalogImportSummary;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSpuCreateRequest;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.catalog.persistence.CatalogAttributeOutboxRecord;
import com.bytedance.ai.catalog.persistence.CatalogAttributeOutboxRepository;
import com.bytedance.ai.document.api.DocumentIndexRequestedEvent;
import com.bytedance.ai.indexing.api.IndexingCommandFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
 * 因为属性抽取链路（producer / dispatcher / listener）在构造期已经依赖 RagProperties，
 * 这条依赖通过 {@code @EnableConfigurationProperties} 注册在 RagConfiguration，
 * 完整启动上下文最直接地保证它存在。
 *
 * <p>{@code rag.catalog.enabled=false} 关掉 listener 实际调 LLM；
 * {@code rag.rocketmq.enabled=false} 关掉 producer 与 dispatcher 装配，
 * outbox 行入库后保持 PENDING 即可，用于断言"导入事务成功写出 outbox 行"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(CatalogModuleTests.CatalogTestEventProbe.class)
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-modulith.sql",
        "rag.catalog.enabled=false",
        "rag.rocketmq.enabled=false"
})
class CatalogModuleTests {

    @Autowired
    private CatalogCommandFacade catalogCommandFacade;

    @Autowired
    private CatalogQueryFacade catalogQueryFacade;

    @Autowired
    private CatalogAttributeOutboxRepository attributeOutboxRepository;

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
    void importBatchDoubleWritesAndEnqueuesOutbox() {
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
                    .as("应发布 DocumentIndexRequestedEvent（catalog → document 跨模块事件保留）")
                    .anyMatch(e -> e.documentId().equals(view.documentId())
                            && "document-command-service".equals(e.triggeredBy()));

            CatalogAttributeOutboxRecord row = attributeOutboxRepository.findLatestBySpuId(spuId).orElseThrow();
            assertThat(row.status())
                    .as("rag.rocketmq.enabled=false 时 outbox 行应保持 PENDING")
                    .isEqualTo("PENDING");
            assertThat(row.externalRef()).isEqualTo("SPU-M-1");
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
     * 用同步 {@link EventListener} 捕获跨模块事件 {@link DocumentIndexRequestedEvent}，
     * 验证 catalog 仍然按 document 模块的契约发起索引；catalog 内部不再有事件路径，
     * 改由 outbox 表承载，详见 {@link CatalogAttributeOutboxRepository}。
     */
    @Component
    static class CatalogTestEventProbe {
        private final List<DocumentIndexRequestedEvent> indexEvents = new CopyOnWriteArrayList<>();

        @EventListener
        void onIndex(DocumentIndexRequestedEvent event) {
            indexEvents.add(event);
        }

        List<DocumentIndexRequestedEvent> indexEvents() {
            return List.copyOf(indexEvents);
        }

        void clear() {
            indexEvents.clear();
        }
    }
}
