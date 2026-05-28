package com.bytedance.ai.graph.catalog.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * catalog_sku 表的记录模型。
 *
 * @param id        主键
 * @param spuId     所属 SPU id（应用层维护一致性，无数据库外键）
 * @param skuCode   SKU 业务编码（在 SPU 内唯一）
 * @param specJson  规格 KV，例如 {"color":"black","size":"M"}
 * @param price     价格
 * @param stock     库存
 * @param status    业务状态：ACTIVE / REMOVED
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record CatalogSkuRecord(
        Long id,
        Long spuId,
        String skuCode,
        Map<String, Object> specJson,
        BigDecimal price,
        Integer stock,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
