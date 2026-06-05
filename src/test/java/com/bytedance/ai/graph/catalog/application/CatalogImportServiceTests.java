package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogSpuCreateRequest;
import com.bytedance.ai.graph.catalog.persistence.CatalogAttributeOutboxRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogSpuRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogSpuRepository;
import com.bytedance.ai.document.api.DocumentCommandFacade;
import com.bytedance.ai.document.api.RagDocumentCreateRequest;
import com.bytedance.ai.document.api.RagDocumentView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CatalogImportService 单元测试：覆盖双写 -> 回填 document_id -> outbox 入队 这一关键路径，
 * 以及 createDocument 失败时的异常传播（让外层 REQUIRES_NEW 事务回滚，连带 outbox 行也回滚）。
 */
class CatalogImportServiceTests {

    private CatalogSpuRepository spuRepository;
    private CatalogSkuRepository skuRepository;
    private DocumentCommandFacade documentCommandFacade;
    private CatalogAttributeOutboxRepository attributeOutboxRepository;
    private RagJsonCodec jsonCodec;
    private CatalogImportService importService;

    @BeforeEach
    void setUp() {
        spuRepository = mock(CatalogSpuRepository.class);
        skuRepository = mock(CatalogSkuRepository.class);
        documentCommandFacade = mock(DocumentCommandFacade.class);
        attributeOutboxRepository = mock(CatalogAttributeOutboxRepository.class);
        jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
        importService = new CatalogImportService(
                spuRepository,
                skuRepository,
                documentCommandFacade,
                new SpuMarkdownRenderer(),
                attributeOutboxRepository,
                jsonCodec
        );
    }

    @Test
    void importOneWritesSpuSkusDocumentAndEnqueuesOutbox() {
        CatalogSpuCreateRequest item = buildRequest("SPU-1", "短描述");
        CatalogSpuRecord savedSpu = stubSpu(7L, "SPU-1");
        when(spuRepository.save(
                eq("SPU-1"), eq("title"), isNull(), isNull(),
                eq(new BigDecimal("1.0")), eq(new BigDecimal("2.0")),
                eq(3), eq("短描述"), eq(List.of()), isNull()
        )).thenReturn(savedSpu);
        when(documentCommandFacade.createDocument(any(RagDocumentCreateRequest.class)))
                .thenReturn(stubDocumentView(901L));

        Long spuId = importService.importOne(item);

        assertThat(spuId).isEqualTo(7L);
        verify(skuRepository).saveAll(eq(7L), anyList());

        ArgumentCaptor<RagDocumentCreateRequest> docCaptor = ArgumentCaptor.forClass(RagDocumentCreateRequest.class);
        verify(documentCommandFacade).createDocument(docCaptor.capture());
        RagDocumentCreateRequest doc = docCaptor.getValue();
        assertThat(doc.sourceType()).isEqualTo("catalog-spu");
        assertThat(doc.sourceUri()).isEqualTo("catalog://spu/SPU-1");
        assertThat(doc.externalRef()).isEqualTo("SPU-1");
        assertThat(doc.title()).isEqualTo("title");
        assertThat(doc.metadata()).containsEntry("spuId", 7L);
        assertThat(doc.metadata()).containsEntry("sourceType", "catalog-spu");

        verify(spuRepository).attachDocument(7L, 901L);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(attributeOutboxRepository).enqueue(eq(7L), eq("SPU-1"), payloadCaptor.capture());
        Map<String, Object> parsed = jsonCodec.readMap(payloadCaptor.getValue());
        assertThat(parsed.get("triggeredBy")).isEqualTo("import");
        assertThat(parsed.get("enqueuedAtMs")).isInstanceOf(Number.class);
    }

    @Test
    void importOneTruncatesLongTitlesForDocumentCreate() {
        String longTitle = "极".repeat(150);
        CatalogSpuCreateRequest item = new CatalogSpuCreateRequest(
                "SPU-2", longTitle, null, null,
                new BigDecimal("1"), new BigDecimal("2"), 0,
                "desc", List.of(), null,
                List.of(new CatalogSpuCreateRequest.SkuDraft("SKU", Map.of(), new BigDecimal("1"), 1))
        );
        when(spuRepository.save(anyString(), anyString(), any(), any(), any(), any(),
                anyInt(), anyString(), anyList(), any())).thenReturn(stubSpu(8L, "SPU-2"));
        when(documentCommandFacade.createDocument(any())).thenReturn(stubDocumentView(902L));

        importService.importOne(item);

        ArgumentCaptor<RagDocumentCreateRequest> docCaptor = ArgumentCaptor.forClass(RagDocumentCreateRequest.class);
        verify(documentCommandFacade).createDocument(docCaptor.capture());
        assertThat(docCaptor.getValue().title()).hasSize(100);
    }

    @Test
    void importOneBubblesUpWhenCreateDocumentFails() {
        CatalogSpuCreateRequest item = buildRequest("SPU-3", "desc");
        when(spuRepository.save(anyString(), anyString(), any(), any(), any(), any(),
                anyInt(), anyString(), anyList(), any())).thenReturn(stubSpu(9L, "SPU-3"));
        when(documentCommandFacade.createDocument(any()))
                .thenThrow(new IllegalStateException("document failed"));

        assertThatThrownBy(() -> importService.importOne(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("document failed");

        verify(spuRepository, never()).attachDocument(any(), any());
        verify(attributeOutboxRepository, never()).enqueue(anyLong(), anyString(), anyString());
    }

    private CatalogSpuCreateRequest buildRequest(String externalRef, String desc) {
        return new CatalogSpuCreateRequest(
                externalRef, "title", null, null,
                new BigDecimal("1.0"), new BigDecimal("2.0"), 3,
                desc, List.of(), null,
                List.of(new CatalogSpuCreateRequest.SkuDraft("SKU-A", Map.of(), new BigDecimal("1.0"), 2))
        );
    }

    private CatalogSpuRecord stubSpu(Long id, String externalRef) {
        return new CatalogSpuRecord(
                id, externalRef, "title", null, null, null, null, 0, "desc",
                List.of(), null, Map.of(), "PENDING", 0, null, null,
                "ACTIVE", 0L, null, null, null
        );
    }

    private RagDocumentView stubDocumentView(Long id) {
        return new RagDocumentView(
                id, "catalog-spu", "catalog://spu/x", "ext",
                "title", "PENDING", 0, 0, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now()
        );
    }
}
