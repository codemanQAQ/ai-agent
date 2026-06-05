package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogCommandFacade;
import com.bytedance.ai.graph.catalog.api.CatalogImportRequest;
import com.bytedance.ai.graph.catalog.api.CatalogImportSummary;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Offline import orchestration for the local ecommerce_agent_dataset.
 */
@Service
public class EcommerceDatasetImportService {

    private final EcommerceDatasetCatalogImportAdapter importAdapter;
    private final CatalogCommandFacade catalogCommandFacade;

    public EcommerceDatasetImportService(
            EcommerceDatasetCatalogImportAdapter importAdapter,
            CatalogCommandFacade catalogCommandFacade
    ) {
        this.importAdapter = importAdapter;
        this.catalogCommandFacade = catalogCommandFacade;
    }

    public CatalogImportSummary importDataset(Path datasetRoot) {
        CatalogImportRequest request = importAdapter.adaptDirectory(datasetRoot);
        return catalogCommandFacade.importBatch(request);
    }
}
