package com.involutionhell.backend.rag.retrieval.application;

import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.indexing.api.IndexingChunkQueryFacade;
import com.involutionhell.backend.rag.indexing.api.RagChunkSearchView;
import com.involutionhell.backend.rag.retrieval.api.RagAskFacade;
import com.involutionhell.backend.rag.retrieval.api.RagAnswerResponse;
import com.involutionhell.backend.rag.retrieval.api.RagAskRequest;
import com.involutionhell.backend.rag.retrieval.api.RagContextView;
import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import com.involutionhell.backend.rag.shared.metadata.RagChunkMetadataHelper;
import com.involutionhell.backend.rag.shared.metadata.RagChunkMetadataView;
import com.involutionhell.backend.rag.retrieval.service.RagAnswerGenerator;
import com.involutionhell.backend.rag.retrieval.service.RagAnswerResult;
import com.involutionhell.backend.rag.retrieval.service.RagDocumentJoiner;
import com.involutionhell.backend.rag.retrieval.service.RagQueryExpander;
import com.involutionhell.backend.rag.retrieval.service.RagQueryExpansionResult;
import com.involutionhell.backend.rag.retrieval.service.RagQueryTransformationResult;
import com.involutionhell.backend.rag.retrieval.service.RagQueryTransformer;
import com.involutionhell.backend.rag.retrieval.service.RagRetriever;
import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class RagAskService implements RagAskFacade {

    private static final Logger log = LoggerFactory.getLogger(RagAskService.class);

    private final IndexingChunkQueryFacade indexingChunkQueryFacade;
    private final RagRetriever ragRetriever;
    private final RagQueryTransformer ragQueryTransformer;
    private final RagQueryExpander ragQueryExpander;
    private final RagDocumentJoiner ragDocumentJoiner;
    private final RagAnswerGenerator answerGenerator;
    private final RagProperties ragProperties;
    private final RagChunkMetadataHelper metadataHelper;

    RagAskService(
            IndexingChunkQueryFacade indexingChunkQueryFacade,
            RagRetriever ragRetriever,
            RagQueryTransformer ragQueryTransformer,
            RagQueryExpander ragQueryExpander,
            RagDocumentJoiner ragDocumentJoiner,
            RagAnswerGenerator answerGenerator,
            RagProperties ragProperties,
            RagChunkMetadataHelper metadataHelper
    ) {
        this.indexingChunkQueryFacade = indexingChunkQueryFacade;
        this.ragRetriever = ragRetriever;
        this.ragQueryTransformer = ragQueryTransformer;
        this.ragQueryExpander = ragQueryExpander;
        this.ragDocumentJoiner = ragDocumentJoiner;
        this.answerGenerator = answerGenerator;
        this.ragProperties = ragProperties;
        this.metadataHelper = metadataHelper;
    }

    @Override
    public RagAnswerResponse ask(RagAskRequest request) {
        int topK = request.topK() == null ? ragProperties.defaultTopK() : request.topK();
        RagSearchFilter filter = RagSearchFilter.of(
                request.sourceUriPrefix(),
                request.tags(),
                request.headingPathContains()
        );
        log.info(
                "RAG ask started: questionLength={}, questionPreview={}, topK={}, historyTurns={}, hasFilter={}",
                request.question() == null ? 0 : request.question().length(),
                RagLogHelper.previewQuestion(request.question()),
                topK,
                request.history() == null ? 0 : request.history().size(),
                !filter.isEmpty()
        );

        RagQueryTransformationResult transformedQuery = ragQueryTransformer.transform(
                request.question(),
                request.history()
        );
        RagQueryExpansionResult expandedQuery = ragQueryExpander.expand(transformedQuery.retrievalQuestion());
        int perQueryTopK = Math.min(
                Math.max(topK * ragProperties.retrieval().multiQueryPerQueryMultiplier(), topK),
                ragProperties.retrieval().multiQueryPerQueryTopKMax()
        );

        if (log.isDebugEnabled()) {
            log.debug(
                    "RAG query preprocessing completed: conversationTurns={}, queryTransformed={}, transformedByModel={}, queryCount={}, expandedByModel={}, perQueryTopK={}, retrievalQueries={}",
                    transformedQuery.conversationTurns(),
                    transformedQuery.queryTransformed(),
                    transformedQuery.transformedByModel(),
                    expandedQuery.retrievalQueries().size(),
                    expandedQuery.expandedByModel(),
                    perQueryTopK,
                    expandedQuery.retrievalQueries().stream()
                            .map(RagLogHelper::previewQuestion)
                            .toList()
            );
        }

        List<List<RagRetrievedChunk>> retrievalResults = expandedQuery.retrievalQueries().stream()
                .map(query -> ragRetriever.search(query, perQueryTopK, filter))
                .toList();
        List<RagRetrievedChunk> contexts = expandNeighborWindow(ragDocumentJoiner.join(retrievalResults, topK));
        RagAnswerResult answer = answerGenerator.generate(request.question(), contexts);

        log.info(
                "RAG ask completed: queryCount={}, contextCount={}, generatedByModel={}, queryExpanded={}, expandedByModel={}",
                expandedQuery.retrievalQueries().size(),
                contexts.size(),
                answer.generatedByModel(),
                expandedQuery.queryExpanded(),
                expandedQuery.expandedByModel()
        );

        return new RagAnswerResponse(
                request.question(),
                expandedQuery.retrievalQueries(),
                expandedQuery.queryExpanded(),
                expandedQuery.expandedByModel(),
                answer.answer(),
                answer.generatedByModel(),
                contexts.stream().map(this::toContextView).toList(),
                false,
                List.of()
        );
    }

    private List<RagRetrievedChunk> expandNeighborWindow(List<RagRetrievedChunk> contexts) {
        int before = ragProperties.retrieval().neighborWindowBefore();
        int after = ragProperties.retrieval().neighborWindowAfter();
        if (contexts == null || contexts.isEmpty() || (before <= 0 && after <= 0)) {
            return contexts;
        }

        LinkedHashMap<String, RagRetrievedChunk> expanded = new LinkedHashMap<>();
        for (RagRetrievedChunk seed : contexts) {
            int seedIndex = seed.chunkIndex() == null ? 0 : seed.chunkIndex();
            int start = Math.max(0, seedIndex - Math.max(before, 0));
            int end = seedIndex + Math.max(after, 0);
            List<RagChunkSearchView> window = indexingChunkQueryFacade.findActiveChunksByDocumentIdAndRange(
                    seed.documentId(),
                    start,
                    end
            );

            if (window.isEmpty()) {
                expanded.putIfAbsent(chunkKey(seed.chunkId(), seed.documentId(), seed.chunkIndex()), seed);
                continue;
            }

            for (RagChunkSearchView row : window) {
                if (seed.chunkId() != null && seed.chunkId().equals(row.chunkId())) {
                    expanded.putIfAbsent(chunkKey(seed.chunkId(), seed.documentId(), seed.chunkIndex()), seed);
                    continue;
                }

                RagChunkMetadataView metadataView = metadataHelper.parse(row.metadata().toString());
                int distance = Math.abs((row.chunkIndex() == null ? seedIndex : row.chunkIndex()) - seedIndex);
                double baseScore = seed.score() == null ? 0.0d : seed.score();
                RagRetrievedChunk neighbor = new RagRetrievedChunk(
                        row.chunkId(),
                        row.documentId(),
                        row.title(),
                        row.sourceType(),
                        row.sourceUri(),
                        row.chunkIndex(),
                        Math.max(0.0d, baseScore - (distance * 0.0001d)),
                        row.chunkText(),
                        metadataView.headingPath(),
                        metadataView.blockType(),
                        metadataView.codeLanguage()
                );
                expanded.putIfAbsent(
                        chunkKey(neighbor.chunkId(), neighbor.documentId(), neighbor.chunkIndex()),
                        neighbor
                );
            }
        }

        log.debug(
                "RAG neighbor expansion completed: seedCount={}, expandedCount={}, before={}, after={}",
                contexts.size(),
                expanded.size(),
                before,
                after
        );
        return new ArrayList<>(expanded.values());
    }

    private String chunkKey(Long chunkId, Long documentId, Integer chunkIndex) {
        if (chunkId != null) {
            return "chunk:" + chunkId;
        }
        return "document:" + documentId + ":" + chunkIndex;
    }

    private RagContextView toContextView(RagRetrievedChunk chunk) {
        return new RagContextView(
                chunk.chunkId(),
                chunk.documentId(),
                chunk.title(),
                chunk.sourceType(),
                chunk.sourceUri(),
                chunk.chunkIndex(),
                chunk.score(),
                chunk.content(),
                chunk.headingPath(),
                chunk.blockType(),
                chunk.codeLanguage()
        );
    }
}
