package com.involutionhell.backend.rag.retrieval.service;

/**
 * 回答生成结果。
 *
 * @param answer 最终回答文本
 * @param generatedByModel 是否由模型直接生成，而不是回退摘要
 */
public record RagAnswerResult(String answer, boolean generatedByModel) {
}
