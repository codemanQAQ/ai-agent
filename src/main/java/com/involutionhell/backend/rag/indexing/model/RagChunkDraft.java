package com.involutionhell.backend.rag.indexing.model;

import java.util.Map;

/**
 * 尚未持久化的文档切片。
 *
 * @param indexGeneration 本次索引生成号
 * @param chunkIndex 切片顺序号
 * @param chunkText 切片正文
 * @param chunkHash 切片内容哈希
 * @param charCount 切片字符数
 * @param tokenCount 切片 token 数；未知时可为空
 * @param vectorId 对应向量 ID；未写入时可为空
 * @param metadata 切片 metadata
 */
public record RagChunkDraft(
        long indexGeneration,
        int chunkIndex,
        String chunkText,
        String chunkHash,
        int charCount,
        Integer tokenCount,
        String vectorId,
        Map<String, Object> metadata
) {
}
