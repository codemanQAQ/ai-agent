package com.involutionhell.backend.rag.indexing.persistence;

import com.involutionhell.backend.rag.indexing.persistence.RagEmbeddingCacheDraft;
import java.util.List;
import java.util.Map;

/**
 * Embedding 缓存仓储。
 */
public interface RagEmbeddingCacheRepository {

    /**
     * 批量读取指定模型与维度下的缓存向量 JSON。
     */
    Map<String, String> findEmbeddingJsonByChunkHashes(List<String> chunkHashes, String embeddingModel, int embeddingDimension);

    /**
     * 批量写入或更新 embedding 缓存。
     */
    void saveAll(List<RagEmbeddingCacheDraft> drafts);
}
