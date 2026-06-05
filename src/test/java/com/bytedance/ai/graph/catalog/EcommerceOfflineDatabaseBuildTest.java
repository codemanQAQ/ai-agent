package com.bytedance.ai.graph.catalog;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import com.bytedance.ai.graph.catalog.api.CatalogImportSummary;
import com.bytedance.ai.graph.catalog.application.EcommerceDatasetImportService;
import com.bytedance.ai.indexing.api.IndexingCommandFacade;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds a file-based offline H2 database for local catalog/RAG validation.
 *
 * <p>This test intentionally writes to {@code target/offline-db/ecommerce-agent-offline}.
 * Run it when a local SQL snapshot of {@code ecommerce_agent_dataset} is needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:file:./target/offline-db/ecommerce-agent-offline;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-modulith.sql",
        "rag.catalog.enabled=false",
        "rag.rocketmq.enabled=false"
})
class EcommerceOfflineDatabaseBuildTest {

    private static final Path DATASET_ROOT = Path.of("../ecommerce_agent_dataset");
    private static final Path OFFLINE_DB = Path.of("target/offline-db/ecommerce-agent-offline");

    static {
        for (String suffix : new String[]{".mv.db", ".trace.db"}) {
            try {
                Files.deleteIfExists(Path.of(OFFLINE_DB + suffix));
            } catch (Exception exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }

    @Autowired
    private EcommerceDatasetImportService importService;

    @Autowired
    private CatalogQueryFacade catalogQueryFacade;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Bypass the existing indexing module's PostgreSQL-specific SQL in H2.
     * The offline DB still receives rag_documents for later indexing validation.
     */
    @MockitoBean
    private IndexingCommandFacade indexingCommandFacade;

    @MockitoBean(name = "intentChatClient")
    private ChatClient intentChatClient;

    @Test
    void buildsOfflineDatabaseFromFullDataset() {
        CatalogImportSummary summary = importService.importDataset(DATASET_ROOT);

        assertThat(summary.total()).isEqualTo(100);
        assertThat(summary.succeeded()).isEqualTo(100);
        assertThat(summary.failed()).isZero();

        Integer spuCount = jdbc.queryForObject("SELECT count(*) FROM catalog_spu", Integer.class);
        Integer skuCount = jdbc.queryForObject("SELECT count(*) FROM catalog_sku", Integer.class);
        Integer ragDocumentCount = jdbc.queryForObject(
                "SELECT count(*) FROM rag_documents WHERE source_type = 'catalog-spu'",
                Integer.class
        );
        Integer missingDocumentCount = jdbc.queryForObject(
                "SELECT count(*) FROM catalog_spu WHERE document_id IS NULL",
                Integer.class
        );

        assertThat(spuCount).isEqualTo(100);
        assertThat(skuCount).isEqualTo(585);
        assertThat(ragDocumentCount).isEqualTo(100);
        assertThat(missingDocumentCount).isZero();

        CatalogSpuView iphone = catalogQueryFacade.findSpuByExternalRef("p_digital_001").orElseThrow();
        assertThat(iphone.categoryPath()).isEqualTo("数码电子/智能手机");
        assertThat(iphone.priceMin()).isEqualByComparingTo("8999.00");
        assertThat(iphone.priceMax()).isEqualByComparingTo("12499.00");
        assertThat(iphone.stock()).isEqualTo(900);
        assertThat(iphone.skus()).hasSize(9);
        assertThat(iphone.documentId()).isNotNull();
    }
}
