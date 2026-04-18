package com.involutionhell.backend.rag.indexing.persistence;

/**
 * 待写入 embedding 缓存的数据。
 *
 * @param chunkHash 切片哈希
 * @param embeddingModel embedding 模型名
 * @param embeddingDimension 向量维度
 * @param embeddingJson 向量 JSON 内容
 */
public record RagEmbeddingCacheDraft(
        String chunkHash,
        String embeddingModel,
        int embeddingDimension,
        String embeddingJson
) {
}
