package com.involutionhell.backend.rag.indexing.api;

/**
 * 对外暴露的索引写侧入口。
 */
public interface IndexingCommandFacade {

    void requestIndexing(Long documentId, String contentSha256, String triggeredBy);

    void cleanupPendingIndexing(Long documentId);
}
