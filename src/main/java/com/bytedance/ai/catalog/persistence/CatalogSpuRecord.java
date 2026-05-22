package com.bytedance.ai.catalog.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * catalog_spu 表的记录模型。
 *
 * @param id                      主键
 * @param externalRef             业务唯一编号（导入侧提供）
 * @param title                   商品标题
 * @param brand                   品牌
 * @param categoryPath            类目路径（"服装/箱包/双肩包"）
 * @param priceMin                展示价区间下界
 * @param priceMax                展示价区间上界
 * @param stock                   SPU 维度总库存
 * @param descriptionMd           商品长描述（Markdown 原文）
 * @param images                  商品图片 URL 列表
 * @param videoUrl                商品视频 URL
 * @param attributesJson          LLM 异步抽取的结构化属性（tags / usage_scenes / features ...）
 * @param attributesStatus        属性抽取状态：PENDING / RUNNING / DONE / FAILED / SKIPPED
 * @param attributesAttemptCount  属性抽取累计尝试次数
 * @param attributesLastError     属性抽取最近一次失败信息
 * @param attributesAttemptedAt   属性抽取最近一次尝试时间
 * @param status                  SPU 业务状态：ACTIVE / DRAFT / REMOVED
 * @param version                 乐观锁版本号
 * @param documentId              关联的 rag_documents.id（双写后回填）
 * @param createdAt               创建时间
 * @param updatedAt               更新时间
 */
public record CatalogSpuRecord(
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
        Map<String, Object> attributesJson,
        String attributesStatus,
        Integer attributesAttemptCount,
        String attributesLastError,
        OffsetDateTime attributesAttemptedAt,
        String status,
        Long version,
        Long documentId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
