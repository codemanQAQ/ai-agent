package com.bytedance.ai.graph.catalog.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * catalog_spu 仓储。
 *
 * <p>状态机：attributes_status 由 catalog 内部 worker 驱动；status 表征商品上下架业务态。
 * 不依赖数据库外键，与 catalog_sku、rag_documents 的关联由应用层维护。
 */
public interface CatalogSpuRepository {

    /**
     * 新建 SPU 主记录。document_id 在双写完成后通过 {@link #attachDocument} 回填。
     */
    CatalogSpuRecord save(
            String externalRef,
            String title,
            String brand,
            String categoryPath,
            BigDecimal priceMin,
            BigDecimal priceMax,
            int stock,
            String descriptionMd,
            List<String> images,
            String videoUrl
    );

    /**
     * 双写 rag_documents 成功后，把 documentId 回填到 SPU 主记录上。
     */
    void attachDocument(Long spuId, Long documentId);

    Optional<CatalogSpuRecord> findById(Long id);

    Optional<CatalogSpuRecord> findByExternalRef(String externalRef);

    List<CatalogSpuRecord> searchActiveByKeyword(String keyword, int limit);

    /**
     * 按价格区间浏览在售商品（无关键词/类目时的兜底，如"送礼 预算500"）。
     * min/max 任一为 null 表示该侧不限；跨多类目按价格升序返回，便于下游多样化与预算贴合。
     */
    List<CatalogSpuRecord> browseActiveByPrice(BigDecimal priceMin, BigDecimal priceMax, int limit);

    /** 在售商品去重的顶级类目（category_path 第一段），按字母序返回。 */
    List<String> listActiveTopCategories();

    boolean decreaseStock(Long spuId, int quantity);

    /**
     * 标记一次属性抽取尝试开始：PENDING/FAILED -> RUNNING，并累加 attempt_count。
     * 若当前状态非 PENDING/FAILED，则返回 false（避免并发覆盖）。
     */
    boolean markAttributeExtractionRunning(Long spuId);

    /**
     * 标记属性抽取成功并回填 attributes_json。
     */
    void markAttributeExtractionSucceeded(Long spuId, Map<String, Object> attributesJson);

    /**
     * 标记属性抽取失败，附带错误信息。
     */
    void markAttributeExtractionFailed(Long spuId, String errorMessage);

    /**
     * 列出所有处于 PENDING 状态的 SPU，供手动重试或补偿任务使用。
     */
    List<CatalogSpuRecord> findByAttributesStatus(String status, int limit);
}
