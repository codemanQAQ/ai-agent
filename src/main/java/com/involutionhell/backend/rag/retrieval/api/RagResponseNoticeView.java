package com.involutionhell.backend.rag.retrieval.api;

/**
 * RAG 问答过程中产生的降级/告警提示。
 *
 * @param stage 所属阶段
 * @param code 提示编码
 * @param message 面向前端的提示文案
 */
public record RagResponseNoticeView(
        String stage,
        String code,
        String message
) {
}
