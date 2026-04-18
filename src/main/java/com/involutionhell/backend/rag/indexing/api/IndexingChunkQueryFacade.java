package com.involutionhell.backend.rag.indexing.api;

import java.util.List;
import java.util.Set;

/**
 * 对 retrieval 暴露的切片只读查询入口。
 */
public interface IndexingChunkQueryFacade {

    List<RagChunkSearchView> findKeywordCandidates(Set<String> tokens, int limit);

    List<RagChunkSearchView> findActiveChunksByDocumentIdAndRange(Long documentId, int startChunkIndex, int endChunkIndex);

    List<RagChunkSearchView> findSearchableByVectorIds(List<String> vectorIds);
}
