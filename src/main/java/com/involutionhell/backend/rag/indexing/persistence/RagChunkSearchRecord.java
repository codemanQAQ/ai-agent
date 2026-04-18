package com.involutionhell.backend.rag.indexing.persistence;

import java.util.Map;

/**
 * 检索时需要的切片与文档关联信息。
 *
 * @param chunkId 切片主键
 * @param documentId 文档主键
 * @param title 文档标题
 * @param sourceType 文档来源类型
 * @param sourceUri 文档来源 URI
 * @param externalRef 外部引用标识
 * @param indexGeneration 当前切片所属 generation
 * @param chunkIndex 切片顺序号
 * @param chunkText 切片正文
 * @param vectorId 向量存储中的向量 ID
 * @param metadata 切片 metadata
 */
public record RagChunkSearchRecord(
        Long chunkId,
        Long documentId,
        String title,
        String sourceType,
        String sourceUri,
        String externalRef,
        Long indexGeneration,
        Integer chunkIndex,
        String chunkText,
        String vectorId,
        Map<String, Object> metadata
) {
}
