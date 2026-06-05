package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogImportRequest;
import com.bytedance.ai.graph.catalog.api.CatalogImportSummary;
import com.bytedance.ai.graph.catalog.api.CatalogSpuCreateRequest;
import com.bytedance.ai.graph.catalog.persistence.CatalogAttributeOutboxRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogSpuRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogSpuRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CatalogCommandService 编排层单元测试：聚焦 importBatch 的"部分成功"汇总语义
 * 与 requestAttributeExtraction 的 outbox 入队（取代旧的事件发布）。
 */
class CatalogCommandServiceTests {

    private CatalogImportService catalogImportService;
    private CatalogSpuRepository spuRepository;
    private CatalogAttributeOutboxRepository attributeOutboxRepository;
    private RagJsonCodec jsonCodec;
    private CatalogCommandService commandService;

    @BeforeEach
    void setUp() {
        catalogImportService = mock(CatalogImportService.class);
        spuRepository = mock(CatalogSpuRepository.class);
        attributeOutboxRepository = mock(CatalogAttributeOutboxRepository.class);
        jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
        commandService = new CatalogCommandService(
                catalogImportService,
                spuRepository,
                attributeOutboxRepository,
                jsonCodec
        );
    }

    @Test
    void importBatchAggregatesSuccessAndFailure() {
        CatalogSpuCreateRequest ok = sampleItem("SPU-OK");
        CatalogSpuCreateRequest bad = sampleItem("SPU-BAD");
        when(catalogImportService.importOne(ok)).thenReturn(101L);
        when(catalogImportService.importOne(bad)).thenThrow(new IllegalStateException("duplicate external_ref"));

        CatalogImportSummary summary = commandService.importBatch(new CatalogImportRequest(List.of(ok, bad)));

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.succeededIds()).containsExactly(101L);
        assertThat(summary.failures()).hasSize(1);
        assertThat(summary.failures().get(0).externalRef()).isEqualTo("SPU-BAD");
        assertThat(summary.failures().get(0).reason()).contains("duplicate external_ref");
    }

    @Test
    void requestAttributeExtractionEnqueuesOutboxWithManualTrigger() {
        Long spuId = 42L;
        when(spuRepository.findById(spuId)).thenReturn(Optional.of(stubSpu(spuId, "SPU-42")));

        commandService.requestAttributeExtraction(spuId);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(attributeOutboxRepository).enqueue(eq(spuId), eq("SPU-42"), payloadCaptor.capture());

        Map<String, Object> parsed = jsonCodec.readMap(payloadCaptor.getValue());
        assertThat(parsed.get("triggeredBy")).isEqualTo("manual-retry");
        assertThat(parsed.get("enqueuedAtMs")).isInstanceOf(Number.class);
    }

    @Test
    void requestAttributeExtractionThrowsWhenSpuMissing() {
        when(spuRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commandService.requestAttributeExtraction(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
        verify(attributeOutboxRepository, never()).enqueue(anyLong(), anyString(), anyString());
    }

    private CatalogSpuCreateRequest sampleItem(String externalRef) {
        return new CatalogSpuCreateRequest(
                externalRef,
                "title-" + externalRef,
                null,
                null,
                new BigDecimal("1"),
                new BigDecimal("2"),
                1,
                "desc",
                List.of(),
                null,
                List.of(new CatalogSpuCreateRequest.SkuDraft("SKU-" + externalRef, Map.of(), new BigDecimal("1"), 1))
        );
    }

    private CatalogSpuRecord stubSpu(Long id, String externalRef) {
        return new CatalogSpuRecord(
                id, externalRef, "t", null, null, null, null, 0, "d", List.of(),
                null, Map.of(), "PENDING", 0, null, null, "ACTIVE", 0L, null, null, null
        );
    }
}
