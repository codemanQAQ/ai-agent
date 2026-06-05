package com.bytedance.ai.graph.catalog.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * SPU 落地页视图，供客户端商品详情页消费。
 *
 * @param id                主键
 * @param externalRef       业务编号
 * @param title             标题
 * @param brand             品牌
 * @param categoryPath      类目路径
 * @param priceMin          展示价区间下界
 * @param priceMax          展示价区间上界
 * @param stock             总库存
 * @param descriptionMd     长描述（Markdown 原文）
 * @param images            图片列表
 * @param videoUrl          视频地址
 * @param attributes        LLM 抽取的结构化属性
 * @param attributesStatus  属性抽取状态：PENDING/RUNNING/DONE/FAILED/SKIPPED
 * @param status            SPU 业务状态
 * @param documentId        关联的 rag_documents 主键
 * @param skus              SKU 列表
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 */
public record CatalogSpuView(
        Long id,
        String externalRef,
        String title,
        String brand,
        String categoryPath,
        BigDecimal priceMin,
        BigDecimal priceMax,
        Integer stock,
        String descriptionMd,
        List<String> images,
        String videoUrl,
        Map<String, Object> attributes,
        String attributesStatus,
        String status,
        Long documentId,
        List<CatalogSkuView> skus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
