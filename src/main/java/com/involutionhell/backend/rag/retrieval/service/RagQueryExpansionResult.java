package com.involutionhell.backend.rag.retrieval.service;

import java.util.List;

/**
 * 多查询扩展结果，区分原始问题和实际用于检索的 query 列表。
 *
 * @param originalQuestion 用户原始问题
 * @param retrievalQueries 实际参与检索的 query 列表
 * @param queryExpanded 是否发生过扩展
 * @param expandedByModel 是否由模型生成了扩展 query
 */
public record RagQueryExpansionResult(
        String originalQuestion,
        List<String> retrievalQueries,
        boolean queryExpanded,
        boolean expandedByModel
) {
}
