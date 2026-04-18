package com.involutionhell.backend.rag.retrieval.api;

import java.util.List;

/**
 * RAG 问答响应。
 *
 * @param question 原始问题
 * @param retrievalQueries 实际执行过的检索 query 列表
 * @param queryExpanded 是否发生查询扩展
 * @param expandedByModel 扩展是否由模型生成
 * @param answer 最终回答
 * @param generatedByModel 回答是否由模型生成
 * @param contexts 返回给前端的上下文片段
 * @param degraded 本次问答是否发生过降级
 * @param notices 本次问答的降级提示
 */
public record RagAnswerResponse(
        String question,
        List<String> retrievalQueries,
        boolean queryExpanded,
        boolean expandedByModel,
        String answer,
        boolean generatedByModel,
        List<RagContextView> contexts,
        boolean degraded,
        List<RagResponseNoticeView> notices
) {
}
