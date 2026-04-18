package com.involutionhell.backend.rag.retrieval.api;

import java.util.List;

/**
 * 返回给前端的上下文片段视图。
 *
 * @param chunkId 切片主键
 * @param documentId 文档主键
 * @param title 文档标题
 * @param sourceType 来源类型
 * @param sourceUri 来源 URI
 * @param chunkIndex 切片顺序号
 * @param score 检索分数
 * @param content 切片正文
 * @param headingPath 标题路径
 * @param blockType 块类型
 * @param codeLanguage 代码语言
 */
public record RagContextView(
        Long chunkId,
        Long documentId,
        String title,
        String sourceType,
        String sourceUri,
        Integer chunkIndex,
        Double score,
        String content,
        List<String> headingPath,
        String blockType,
        String codeLanguage
) {
}
