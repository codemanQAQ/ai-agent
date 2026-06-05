package com.bytedance.ai.retrieval.spi;

import java.util.Map;

/**
 * 单条商品检索命中。
 *
 * <p>retrieval 内部按 documentId 聚合 chunk 后产出一条 SPU 级 hit；
 * SPU 的实时业务字段（price / stock）由调用方再从 catalog 取，**不**走 chunk metadata
 * （chunk metadata 在索引那一刻就被快照），避免给上层喂陈旧数据。
 *
 * @param spuId       商品 SPU id（从 chunk metadata.spuId 提取，可能为 null —— catalog 之外的来源没有该字段）
 * @param documentId  关联的 rag_documents.id
 * @param externalRef SPU 业务编号；catalog 来源必填，其它来源可能为 null
 * @param score       综合排序分（0~1）
 * @param chunkType   触发该命中的最佳 chunk_type；用于上层做加权解释
 * @param snippet     给 LLM 的简短上下文片段（来自命中 chunk 的正文）
 * @param metadata    额外原始 metadata（剩余字段透传，供上层观察）
 */
public record ProductSearchHit(
        Long spuId,
        Long documentId,
        String externalRef,
        double score,
        String chunkType,
        String snippet,
        Map<String, Object> metadata
) {
}
