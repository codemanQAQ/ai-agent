package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.indexing.api.IndexingChunkQueryFacade;
import com.involutionhell.backend.rag.indexing.api.RagChunkSearchView;
import com.involutionhell.backend.rag.indexing.persistence.RagChunkRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagChunkSearchRecord;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
class IndexingChunkQueryService implements IndexingChunkQueryFacade {

    private final RagChunkRepository chunkRepository;

    IndexingChunkQueryService(RagChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Override
    public List<RagChunkSearchView> findKeywordCandidates(Set<String> tokens, int limit) {
        return chunkRepository.findKeywordCandidates(tokens, limit).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public List<RagChunkSearchView> findActiveChunksByDocumentIdAndRange(Long documentId, int startChunkIndex, int endChunkIndex) {
        return chunkRepository.findActiveChunksByDocumentIdAndRange(documentId, startChunkIndex, endChunkIndex).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public List<RagChunkSearchView> findSearchableByVectorIds(List<String> vectorIds) {
        return chunkRepository.findSearchableByVectorIds(vectorIds).stream()
                .map(this::toView)
                .toList();
    }

    private RagChunkSearchView toView(RagChunkSearchRecord record) {
        return new RagChunkSearchView(
                record.chunkId(),
                record.documentId(),
                record.title(),
                record.sourceType(),
                record.sourceUri(),
                record.externalRef(),
                record.indexGeneration(),
                record.chunkIndex(),
                record.chunkText(),
                record.vectorId(),
                record.metadata()
        );
    }
}
