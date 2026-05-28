package com.bytedance.ai.graph.catalog.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * catalog_sku 仓储。
 *
 * <p>一次导入一条 SPU 通常带 1~N 个 SKU，使用 {@link #saveAll} 批量插入降低 IO。
 */
public interface CatalogSkuRepository {

    /**
     * 批量插入 SKU。所有记录绑定到同一个 SPU。
     */
    List<CatalogSkuRecord> saveAll(Long spuId, List<SkuDraft> drafts);

    List<CatalogSkuRecord> findBySpuId(Long spuId);

    /**
     * SKU 插入草稿——避免接口里堆参数。
     *
     * @param skuCode  SKU 业务编码
     * @param specJson 规格 KV
     * @param price    价格
     * @param stock    库存
     */
    record SkuDraft(
            String skuCode,
            Map<String, Object> specJson,
            BigDecimal price,
            int stock
    ) {
    }
}
