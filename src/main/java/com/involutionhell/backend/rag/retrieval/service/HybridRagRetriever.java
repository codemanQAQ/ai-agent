package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import java.util.concurrent.Executor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 启用 Milvus 后，组合向量检索和关键词检索，再用 RRF 做融合排序。
 */
@Service
@Primary
@ConditionalOnBean(MilvusVectorStore.class)
@ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "true")
public class HybridRagRetriever implements RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRagRetriever.class);

    private final RagMilvusRetriever ragMilvusRetriever;
    private final KeywordRagRetriever keywordRagRetriever;
    private final RagDocumentJoiner ragDocumentJoiner;
    private final RagProperties ragProperties;

    @Autowired
    public HybridRagRetriever(
            RagMilvusRetriever ragMilvusRetriever,
            KeywordRagRetriever keywordRagRetriever,
            RagDocumentJoiner ragDocumentJoiner,
            RagProperties ragProperties) {
        this.ragMilvusRetriever = ragMilvusRetriever;
        this.keywordRagRetriever = keywordRagRetriever;
        this.ragDocumentJoiner = ragDocumentJoiner;
        this.ragProperties = ragProperties;
    }

    /**
     * 向后兼容旧测试构造器。
     */
    @Deprecated
    public HybridRagRetriever(
            RagMilvusRetriever ragMilvusRetriever,
            KeywordRagRetriever keywordRagRetriever,
            RagDocumentJoiner ragDocumentJoiner,
            Executor ignoredExecutor,
            RagProperties ragProperties,
            Object ignoredMetrics
    ) {
        this(ragMilvusRetriever, keywordRagRetriever, ragDocumentJoiner, ragProperties);
    }

    @Override
    public List<RagRetrievedChunk> search(String question, int topK, RagSearchFilter filter) {
        int perRetrieverTopK = Math.min(
                Math.max(topK * ragProperties.retrieval().hybridPerRetrieverMultiplier(), topK),
                ragProperties.retrieval().hybridPerRetrieverTopKMax());
        List<RagRetrievedChunk> semanticResults = ragMilvusRetriever.search(question, perRetrieverTopK, filter);
        List<RagRetrievedChunk> keywordResults = keywordRagRetriever.search(question, perRetrieverTopK, filter);
        List<RagRetrievedChunk> joined = ragDocumentJoiner.join(List.of(semanticResults, keywordResults), topK);
        log.debug(
                "Hybrid retrieval completed: questionPreview={}, topK={}, perRetrieverTopK={}, semanticCount={}, keywordCount={}, joinedCount={}, hasFilter={}",
                RagLogHelper.previewQuestion(question),
                topK,
                perRetrieverTopK,
                semanticResults.size(),
                keywordResults.size(),
                joined.size(),
                filter != null && !filter.isEmpty());
        return joined;
    }
}
