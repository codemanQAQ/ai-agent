package com.involutionhell.backend.rag.retrieval.service;

/**
 * 查询压缩/改写结果。
 *
 * @param originalQuestion 原始用户问题
 * @param retrievalQuestion 实际进入后续扩写/检索的 query
 * @param queryTransformed 是否发生过查询改写
 * @param transformedByModel 是否由模型完成了改写
 * @param conversationTurns 当前对话总轮数
 */
public record RagQueryTransformationResult(
        String originalQuestion,
        String retrievalQuestion,
        boolean queryTransformed,
        boolean transformedByModel,
        int conversationTurns
) {
}
