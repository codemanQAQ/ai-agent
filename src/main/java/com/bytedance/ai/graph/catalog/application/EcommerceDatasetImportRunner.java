package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogImportSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * Optional one-shot bootstrap runner for loading ecommerce_agent_dataset before serving traffic.
 */
@Component
@ConditionalOnProperty(name = "rag.catalog.dataset-import.enabled", havingValue = "true")
public class EcommerceDatasetImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EcommerceDatasetImportRunner.class);

    private final EcommerceDatasetImportService importService;
    private final String datasetRoot;
    private final boolean failOnError;

    public EcommerceDatasetImportRunner(
            EcommerceDatasetImportService importService,
            @Value("${rag.catalog.dataset-import.root:}") String datasetRoot,
            @Value("${rag.catalog.dataset-import.fail-on-error:true}") boolean failOnError
    ) {
        this.importService = importService;
        this.datasetRoot = datasetRoot;
        this.failOnError = failOnError;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(datasetRoot)) {
            throw new IllegalStateException("rag.catalog.dataset-import.root must be set when dataset import is enabled");
        }
        CatalogImportSummary summary = importService.importDataset(Path.of(datasetRoot));
        log.info(
                "ecommerce dataset import completed: root={}, total={}, succeeded={}, failed={}",
                datasetRoot,
                summary.total(),
                summary.succeeded(),
                summary.failed()
        );
        if (failOnError && summary.failed() > 0) {
            throw new IllegalStateException("ecommerce dataset import failed for " + summary.failed() + " item(s)");
        }
    }
}
