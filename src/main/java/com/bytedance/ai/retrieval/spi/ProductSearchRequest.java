package com.bytedance.ai.retrieval.spi;

import com.bytedance.ai.shared.metadata.RagSearchFilter;

import java.util.List;

/**
 * 商品检索请求。
 *
 * <p>caller 不需要构造 {@code RagRetrievalRequest} / budget / feedbacks 等 retrieval 内部模型，
 * 只表达"想搜什么、最多要几个、加什么硬过滤"。
 *
 * @param query              用户原始查询文本（必填）
 * @param filter             基础硬过滤；可为 null
 * @param topK               期望返回的 SPU 数；为 null / ≤0 时按 retrieval 默认 topK
 * @param includeChunkTypes  仅检索特定 chunk type 命中的 chunk；为空表示不限制。
 *                            常用值：{@code TITLE} / {@code DESC} / {@code ATTR} / {@code REVIEW} / {@code IMAGE}。
 * @param restrictToSpuRefs  REFINE 等子集重排场景下，将 hit 范围限制到指定的 externalRef 列表（catalog SPU 业务编号）。
 *                            为空 / null 表示不限制；非空时会在 retrieval 内部聚合后做后过滤。
 */
public record ProductSearchRequest(
        String query,
        RagSearchFilter filter,
        Integer topK,
        List<String> includeChunkTypes,
        List<String> restrictToSpuRefs
) {

    public ProductSearchRequest {
        includeChunkTypes = includeChunkTypes == null ? List.of() : List.copyOf(includeChunkTypes);
        restrictToSpuRefs = restrictToSpuRefs == null ? List.of() : List.copyOf(restrictToSpuRefs);
    }

    public ProductSearchRequest(String query, RagSearchFilter filter, Integer topK, List<String> includeChunkTypes) {
        this(query, filter, topK, includeChunkTypes, List.of());
    }
}
