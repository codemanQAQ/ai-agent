package com.bytedance.ai.graph.catalog.api;

import java.math.BigDecimal;
import java.util.Map;

/**
 * SKU 落地页视图。
 *
 * @param id        SKU 主键
 * @param skuCode   业务编码
 * @param specJson  规格 KV
 * @param price     价格
 * @param stock     库存
 * @param status    业务状态
 */
public record CatalogSkuView(
        Long id,
        String skuCode,
        Map<String, Object> specJson,
        BigDecimal price,
        Integer stock,
        String status
) {
}
