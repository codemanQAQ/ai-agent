package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 参考 Spring AI DocumentJoiner，把多路检索结果合并、去重并重排。
 */
@Service
public class RagDocumentJoiner {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentJoiner.class);

    private final RagProperties ragProperties;

    public RagDocumentJoiner(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 使用 RRF(Reciprocal Rank Fusion) 合并多路 query 的召回结果。
     */
    public List<RagRetrievedChunk> join(List<List<RagRetrievedChunk>> retrievalResults, int topK) {
        Map<String, JoinedChunk> joined = new LinkedHashMap<>();
        double reciprocalRankK = ragProperties.retrieval().rrfK();

        for (List<RagRetrievedChunk> resultSet : retrievalResults) {
            for (int rank = 0; rank < resultSet.size(); rank++) {
                RagRetrievedChunk chunk = resultSet.get(rank);
                String key = buildKey(chunk);
                JoinedChunk accumulator = joined.computeIfAbsent(key, ignored -> new JoinedChunk(chunk));
                accumulator.add(chunk, rank, reciprocalRankK);
            }
        }

        List<RagRetrievedChunk> results = joined.values().stream()
                .sorted(Comparator.comparingDouble(JoinedChunk::joinedScore).reversed()
                        .thenComparing(Comparator.comparingDouble(JoinedChunk::maxRetrieverScore).reversed())
                        .thenComparing(joinedChunk -> joinedChunk.representative().documentId())
                        .thenComparing(joinedChunk -> joinedChunk.representative().chunkIndex()))
                .limit(topK)
                .map(JoinedChunk::toRetrievedChunk)
                .toList();
        log.debug(
                "RRF join 完成。resultSetCount={}, deduplicatedCount={}, finalCount={}, rrfK={}",
                retrievalResults == null ? 0 : retrievalResults.size(),
                joined.size(),
                results.size(),
                reciprocalRankK
        );
        return results;
    }

    private String buildKey(RagRetrievedChunk chunk) {
        if (chunk.chunkId() != null) {
            return "chunk:" + chunk.chunkId();
        }
        return "document:" + chunk.documentId() + ":" + chunk.chunkIndex();
    }

    private static final class JoinedChunk {

        private RagRetrievedChunk representative;
        private double joinedScore;
        private double maxRetrieverScore;

        private JoinedChunk(RagRetrievedChunk initialChunk) {
            this.representative = initialChunk;
            this.joinedScore = 0.0d;
            this.maxRetrieverScore = initialChunk.score() == null ? 0.0d : initialChunk.score();
        }

        private void add(RagRetrievedChunk chunk, int rank, double reciprocalRankK) {
            double retrieverScore = chunk.score() == null ? 0.0d : chunk.score();
            this.joinedScore += 1.0d / (reciprocalRankK + rank + 1);
            if (retrieverScore > this.maxRetrieverScore) {
                this.maxRetrieverScore = retrieverScore;
                this.representative = chunk;
            }
        }

        private double joinedScore() {
            return joinedScore;
        }

        private double maxRetrieverScore() {
            return maxRetrieverScore;
        }

        private RagRetrievedChunk representative() {
            return representative;
        }

        private RagRetrievedChunk toRetrievedChunk() {
            return new RagRetrievedChunk(
                    representative.chunkId(),
                    representative.documentId(),
                    representative.title(),
                    representative.sourceType(),
                    representative.sourceUri(),
                    representative.chunkIndex(),
                    joinedScore,
                    representative.content(),
                    representative.headingPath(),
                    representative.blockType(),
                    representative.codeLanguage()
            );
        }
    }
}
