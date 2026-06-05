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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单条 SPU 的事务化导入服务。拆出独立 bean 是为了让 Spring 代理生效——
 * {@link CatalogCommandService#importBatch} 调用本类时会通过容器代理，
 * 单条失败由本类抛错回滚，由调用方记录失败明细并继续下一条。
 *
 * <p>导入流程末尾调 {@link CatalogAttributeOutboxRepository#enqueue}，
 * 利用同事务保证「SPU 落库 + 双写 rag_documents + outbox 行」三者要么全部成功要么全部回滚，
 * 后台 dispatcher 看到 outbox 行后才会真正投递 RocketMQ，参见 {@code AGENT.md §3.9}。
 */
@Service
class CatalogImportService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);
    private static final String SOURCE_TYPE = "catalog-spu";
    private static final String IMPORT_TRIGGER = "import";
    private static final int RAG_DOCUMENT_TITLE_MAX = 100;

    private final CatalogSpuRepository spuRepository;
    private final CatalogSkuRepository skuRepository;
    private final DocumentCommandFacade documentCommandFacade;
    private final SpuMarkdownRenderer markdownRenderer;
    private final CatalogAttributeOutboxRepository attributeOutboxRepository;
    private final RagJsonCodec jsonCodec;

    CatalogImportService(
            CatalogSpuRepository spuRepository,
            CatalogSkuRepository skuRepository,
            DocumentCommandFacade documentCommandFacade,
            SpuMarkdownRenderer markdownRenderer,
            CatalogAttributeOutboxRepository attributeOutboxRepository,
            RagJsonCodec jsonCodec
    ) {
        this.spuRepository = spuRepository;
        this.skuRepository = skuRepository;
        this.documentCommandFacade = documentCommandFacade;
        this.markdownRenderer = markdownRenderer;
        this.attributeOutboxRepository = attributeOutboxRepository;
        this.jsonCodec = jsonCodec;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long importOne(CatalogSpuCreateRequest item) {
        CatalogSpuRecord spu = spuRepository.save(
                item.externalRef(),
                item.title(),
                item.brand(),
                item.categoryPath(),
                item.priceMin(),
                item.priceMax(),
                item.stock() == null ? 0 : item.stock(),
                item.descriptionMd(),
                item.images(),
                item.videoUrl()
        );

        List<CatalogSkuRepository.SkuDraft> skuDrafts = item.skus().stream()
                .map(s -> new CatalogSkuRepository.SkuDraft(
                        s.skuCode(),
                        s.specJson(),
                        s.price(),
                        s.stock() == null ? 0 : s.stock()
                ))
                .toList();
        skuRepository.saveAll(spu.id(), skuDrafts);

        String content = markdownRenderer.render(item);
        Map<String, Object> metadata = buildDocumentMetadata(spu, item);

        RagDocumentView created = documentCommandFacade.createDocument(new RagDocumentCreateRequest(
                SOURCE_TYPE,
                buildSourceUri(item.externalRef()),
                item.externalRef(),
                truncateForDocumentTitle(item.title()),
                content,
                metadata
        ));
        spuRepository.attachDocument(spu.id(), created.id());

        attributeOutboxRepository.enqueue(spu.id(), spu.externalRef(), buildOutboxPayload(IMPORT_TRIGGER));

        log.info(
                "catalog SPU imported: spuId={}, externalRef={}, documentId={}",
                spu.id(),
                spu.externalRef(),
                created.id()
        );
        return spu.id();
    }

    private String buildOutboxPayload(String trigger) {
        return jsonCodec.write(Map.of(
                "triggeredBy", trigger,
                "enqueuedAtMs", System.currentTimeMillis()
        ));
    }

    private Map<String, Object> buildDocumentMetadata(CatalogSpuRecord spu, CatalogSpuCreateRequest item) {
        // RagDocumentCreateRequest 强校验 metadata 非空，这里至少塞稳定的可观测字段。
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("spuId", spu.id());
        metadata.put("catalogSpuId", spu.id());
        metadata.put("productId", spu.externalRef());
        metadata.put("externalRef", spu.externalRef());
        metadata.put("sourceType", SOURCE_TYPE);
        if (StringUtils.hasText(item.brand())) {
            metadata.put("brand", item.brand());
        }
        if (StringUtils.hasText(item.categoryPath())) {
            metadata.put("categoryPath", item.categoryPath());
            String[] categorySegments = item.categoryPath().split("/", 2);
            if (StringUtils.hasText(categorySegments[0])) {
                metadata.put("category", categorySegments[0].trim());
            }
            if (categorySegments.length > 1 && StringUtils.hasText(categorySegments[1])) {
                metadata.put("subCategory", categorySegments[1].trim());
            }
        }
        if (item.priceMin() != null) {
            metadata.put("priceMin", item.priceMin());
        }
        if (item.priceMax() != null) {
            metadata.put("priceMax", item.priceMax());
        }
        metadata.put("stock", item.stock() == null ? 0 : item.stock());
        if (item.images() != null && !item.images().isEmpty()) {
            metadata.put("imagePath", item.images().getFirst());
        }
        return metadata;
    }

    private String buildSourceUri(String externalRef) {
        return "catalog://spu/" + externalRef;
    }

    private String truncateForDocumentTitle(String title) {
        if (title == null) {
            return null;
        }
        if (title.length() <= RAG_DOCUMENT_TITLE_MAX) {
            return title;
        }
        return title.substring(0, RAG_DOCUMENT_TITLE_MAX);
    }
}
