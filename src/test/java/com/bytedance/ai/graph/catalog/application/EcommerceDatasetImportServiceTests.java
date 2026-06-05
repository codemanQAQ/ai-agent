package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogCommandFacade;
import com.bytedance.ai.graph.catalog.api.CatalogImportRequest;
import com.bytedance.ai.graph.catalog.api.CatalogImportSummary;
import com.bytedance.ai.graph.catalog.api.CatalogSpuCreateRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcommerceDatasetImportServiceTests {

    @Test
    void importsAdaptedDatasetThroughCatalogCommandFacade() {
        EcommerceDatasetCatalogImportAdapter adapter = mock(EcommerceDatasetCatalogImportAdapter.class);
        CatalogCommandFacade commandFacade = mock(CatalogCommandFacade.class);
        EcommerceDatasetImportService service = new EcommerceDatasetImportService(adapter, commandFacade);
        Path root = Path.of("C:/development/ecommerce_agent_dataset");
        CatalogImportRequest request = new CatalogImportRequest(List.of(sampleItem()));
        CatalogImportSummary expected = new CatalogImportSummary(1, 1, 0, List.of(10L), List.of());
        when(adapter.adaptDirectory(root)).thenReturn(request);
        when(commandFacade.importBatch(request)).thenReturn(expected);

        CatalogImportSummary actual = service.importDataset(root);

        assertThat(actual).isSameAs(expected);
        verify(adapter).adaptDirectory(root);
        verify(commandFacade).importBatch(request);
    }

    private CatalogSpuCreateRequest sampleItem() {
        return new CatalogSpuCreateRequest(
                "p_test_001",
                "测试商品",
                "TestBrand",
                "测试类目",
                new BigDecimal("10"),
                new BigDecimal("10"),
                100,
                "测试描述",
                List.of(),
                null,
                List.of(new CatalogSpuCreateRequest.SkuDraft("sku_1", Map.of(), new BigDecimal("10"), 100))
        );
    }
}
