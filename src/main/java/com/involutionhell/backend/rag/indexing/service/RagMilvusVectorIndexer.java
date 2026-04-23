package com.involutionhell.backend.rag.indexing.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import com.involutionhell.backend.rag.indexing.persistence.RagChunkRecord;
import com.involutionhell.backend.rag.indexing.persistence.RagEmbeddingCacheDraft;
import com.involutionhell.backend.rag.indexing.persistence.RagEmbeddingCacheRepository;
import com.involutionhell.backend.rag.shared.metadata.RagChunkMetadataHelper;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagJsonCodec;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.UpsertParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.EmbeddingUtils;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;

/**
 * 负责复用 embedding 缓存并直接写入 Milvus，避免 chunk 未变化时重复调用 embedding 模型。
 */
@Component
@ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "true")
public class RagMilvusVectorIndexer {

    private static final Logger log = LoggerFactory.getLogger(RagMilvusVectorIndexer.class);
    private static final int MAX_EMBEDDING_BATCH_SIZE = 10;

    private final ObjectProvider<MilvusServiceClient> milvusClientProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final RagEmbeddingCacheRepository embeddingCacheRepository;
    private final RagIndexingMetrics indexingMetrics;
    private final RagProperties ragProperties;
    private final RagJsonCodec jsonCodec;
    private final Gson gson = new Gson();

    public RagMilvusVectorIndexer(
            ObjectProvider<MilvusServiceClient> milvusClientProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            RagEmbeddingCacheRepository embeddingCacheRepository,
            RagIndexingMetrics indexingMetrics,
            RagProperties ragProperties,
            RagJsonCodec jsonCodec
    ) {
        this.milvusClientProvider = milvusClientProvider;
        this.embeddingModelProvider = embeddingModelProvider;
        this.embeddingCacheRepository = embeddingCacheRepository;
        this.indexingMetrics = indexingMetrics;
        this.ragProperties = ragProperties;
        this.jsonCodec = jsonCodec;
    }

    /**
     * 将当前 generation 的切片批量写入 Milvus。
     * stable vectorId 模式下，相同段位通过 Upsert 覆盖旧向量，而不是继续插入新 ID。
     */
    public void add(DocumentIndexingView document, List<RagChunkRecord> chunks) {
        if (!ragProperties.milvus().enabled() || chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException(
                    "Milvus 写入前置条件不满足: milvusEnabled=" + ragProperties.milvus().enabled()
                            + ", chunkCount=" + (chunks == null ? 0 : chunks.size())
            );
        }

        MilvusServiceClient milvusClient = milvusClientProvider.getIfAvailable();
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (milvusClient == null || embeddingModel == null) {
            throw new IllegalStateException(
                    "Milvus 写入依赖缺失: hasMilvusClient=" + (milvusClient != null)
                            + ", hasEmbeddingModel=" + (embeddingModel != null)
            );
        }

        long writeStart = System.nanoTime();
        Map<String, float[]> embeddingsByHash = resolveEmbeddings(chunks, embeddingModel);
        log.debug(
                "Preparing Milvus upsert: documentId={}, contentSha={}, chunkCount={}, uniqueEmbeddingCount={}, collection={}, database={}",
                document.id(),
                RagLogHelper.shortSha(document.contentSha256()),
                chunks.size(),
                embeddingsByHash.size(),
                resolveCollectionName(),
                resolveDatabaseName()
        );

        List<String> ids = new ArrayList<>(chunks.size());
        List<String> contents = new ArrayList<>(chunks.size());
        List<JsonObject> metadatas = new ArrayList<>(chunks.size());
        List<List<Float>> embeddings = new ArrayList<>(chunks.size());

        for (RagChunkRecord chunk : chunks) {
            float[] vector = embeddingsByHash.get(chunk.chunkHash());
            if (vector == null) {
                throw new IllegalStateException("未找到 chunk 的 embedding 缓存: " + chunk.chunkHash());
            }

            ids.add(chunk.vectorId());
            contents.add(chunk.chunkText());
            metadatas.add(gson.toJsonTree(toMetadata(document, chunk)).getAsJsonObject());
            embeddings.add(EmbeddingUtils.toList(vector));
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(MilvusVectorStore.DOC_ID_FIELD_NAME, ids));
        fields.add(new InsertParam.Field(MilvusVectorStore.CONTENT_FIELD_NAME, contents));
        fields.add(new InsertParam.Field(MilvusVectorStore.METADATA_FIELD_NAME, metadatas));
        fields.add(new InsertParam.Field(MilvusVectorStore.EMBEDDING_FIELD_NAME, embeddings));

        UpsertParam.Builder upsertParamBuilder = UpsertParam.newBuilder()
                .withCollectionName(resolveCollectionName())
                .withFields(fields);

        String databaseName = resolveDatabaseName();
        if (StringUtils.hasText(databaseName)) {
            upsertParamBuilder.withDatabaseName(databaseName);
        }

        UpsertParam upsertParam = upsertParamBuilder.build();

        R<?> response = milvusClient.upsert(upsertParam);
        if (response.getException() != null) {
            throw new IllegalStateException("Milvus 向量写入失败", response.getException());
        }

        indexingMetrics.recordMilvusWrite(chunks.size(), Duration.ofNanos(System.nanoTime() - writeStart), true);
        log.info(
                "Milvus upsert completed: documentId={}, contentSha={}, chunkCount={}, collection={}",
                document.id(),
                RagLogHelper.shortSha(document.contentSha256()),
                chunks.size(),
                resolveCollectionName()
        );
    }

    /**
     * 按 vectorId 删除 Milvus 中的向量。
     */
    public void delete(List<String> vectorIds) {
        if (!ragProperties.milvus().enabled() || vectorIds == null || vectorIds.isEmpty()) {
            return;
        }

        MilvusServiceClient milvusClient = milvusClientProvider.getIfAvailable();
        if (milvusClient == null) {
            log.warn("Milvus delete skipped because MilvusServiceClient is unavailable: vectorCount={}", vectorIds.size());
            return;
        }

        DeleteParam.Builder deleteParamBuilder = DeleteParam.newBuilder()
                .withCollectionName(resolveCollectionName())
                .withExpr(buildDeleteExpression(vectorIds));
        log.debug(
                "准备执行 Milvus 删除。collection={}, database={}, vectorCount={}",
                resolveCollectionName(),
                resolveDatabaseName(),
                vectorIds.size()
        );

        String databaseName = resolveDatabaseName();
        if (StringUtils.hasText(databaseName)) {
            deleteParamBuilder.withDatabaseName(databaseName);
        }

        R<?> response = milvusClient.delete(deleteParamBuilder.build());
        if (response.getException() != null) {
            Exception exception = response.getException();
            if (isCollectionMissing(exception)) {
                log.warn(
                        "Milvus delete skipped because collection is missing: collection={}, database={}, vectorCount={}",
                        resolveCollectionName(),
                        databaseName,
                        vectorIds.size()
                );
                return;
            }
            throw new IllegalStateException("Milvus 向量删除失败", exception);
        }

        log.debug(
                "Milvus 向量删除完成。collection={}, database={}, vectorCount={}",
                resolveCollectionName(),
                databaseName,
                vectorIds.size()
        );
    }

    public void deleteByDocumentId(Long documentId) {
        MilvusServiceClient milvusClient = milvusClientProvider.getIfAvailable();
        // 构建 Milvus 的布尔表达式 (假设你在存入时 metadata 里的 key 叫 "documentId")
        String expr = "documentId == " + documentId;

        try {
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName("rag_chunks") // 你的集合名称
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = Objects.requireNonNull(milvusClient).delete(deleteParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Milvus 根据 documentId 删除失败: " + response.getMessage());
            }
        } catch (Exception e) {
            // 捕获特定异常并决定是否抛出
            log.error("Milvus 表达式删除异常, documentId={}, error={}", documentId, e.getMessage(), e);
            throw e;
        }
    }

    private Map<String, float[]> resolveEmbeddings(List<RagChunkRecord> chunks, EmbeddingModel embeddingModel) {
        String model = ragProperties.embeddingModel();
        int dimension = resolveEmbeddingDimension();
        // 相同 chunkHash 的切片复用同一份 embedding，避免同批次内部重复请求模型。
        List<RagChunkRecord> uniqueChunks = chunks.stream()
                .collect(java.util.stream.Collectors.toMap(
                        RagChunkRecord::chunkHash,
                        chunk -> chunk,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();

        Map<String, String> cachedJsonByHash = ragProperties.indexing().embeddingCacheEnabled()
                ? embeddingCacheRepository.findEmbeddingJsonByChunkHashes(
                uniqueChunks.stream().map(RagChunkRecord::chunkHash).toList(),
                model,
                dimension
        )
                : Map.of();

        Map<String, float[]> embeddingsByHash = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : cachedJsonByHash.entrySet()) {
            embeddingsByHash.put(entry.getKey(), jsonCodec.read(entry.getValue(), float[].class));
        }

        List<RagChunkRecord> missingChunks = uniqueChunks.stream()
                .filter(chunk -> !embeddingsByHash.containsKey(chunk.chunkHash()))
                .toList();
        indexingMetrics.recordCacheHits(cachedJsonByHash.size());
        indexingMetrics.recordCacheMisses(missingChunks.size());
        log.debug(
                "Embedding 缓存检查完成。uniqueChunkCount={}, cacheHitCount={}, cacheMissCount={}, model={}, dimension={}",
                uniqueChunks.size(),
                cachedJsonByHash.size(),
                missingChunks.size(),
                model,
                dimension
        );

        if (missingChunks.isEmpty()) {
            return embeddingsByHash;
        }

        List<RagEmbeddingCacheDraft> newCacheEntries = new ArrayList<>(missingChunks.size());
        for (int start = 0; start < missingChunks.size(); start += MAX_EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + MAX_EMBEDDING_BATCH_SIZE, missingChunks.size());
            List<RagChunkRecord> batch = missingChunks.subList(start, end);
            log.debug(
                    "Embedding 批次生成开始。batchStart={}, batchEnd={}, batchSize={}, totalMissingCount={}, model={}",
                    start,
                    end,
                    batch.size(),
                    missingChunks.size(),
                    model
            );
            List<float[]> batchEmbeddings = embeddingModel.embed(batch.stream()
                    .map(RagChunkRecord::chunkText)
                    .toList());
            if (batchEmbeddings.size() != batch.size()) {
                throw new IllegalStateException(
                        "Embedding 返回数量与请求数量不一致: requested=%d, actual=%d"
                                .formatted(batch.size(), batchEmbeddings.size())
                );
            }
            for (int index = 0; index < batch.size(); index++) {
                RagChunkRecord chunk = batch.get(index);
                float[] embedding = batchEmbeddings.get(index);
                embeddingsByHash.put(chunk.chunkHash(), embedding);
                if (ragProperties.indexing().embeddingCacheEnabled()) {
                    newCacheEntries.add(new RagEmbeddingCacheDraft(
                            chunk.chunkHash(),
                            model,
                            dimension,
                            jsonCodec.write(embedding)
                    ));
                }
            }
        }

        if (!newCacheEntries.isEmpty()) {
            embeddingCacheRepository.saveAll(newCacheEntries);
            log.debug("保存新的 embedding 缓存条目。count={}", newCacheEntries.size());
        }
        return embeddingsByHash;
    }

    private Map<String, Object> toMetadata(DocumentIndexingView document, RagChunkRecord chunk) {
        Map<String, Object> metadata = parseChunkMetadata(chunk.metadata());
        metadata.put("vectorId", chunk.vectorId());
        metadata.put("documentId", document.id());
        metadata.put("indexGeneration", chunk.indexGeneration());
        metadata.put("sourceType", document.sourceType());
        metadata.put("sourceUri", defaultString(document.sourceUri()));
        metadata.put("title", defaultString(document.title()));
        metadata.put("chunkIndex", chunk.chunkIndex());
        return metadata;
    }

    private Map<String, Object> parseChunkMetadata(Map<String, Object> metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(metadataJson);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String buildDeleteExpression(List<String> vectorIds) {
        String joinedIds = vectorIds.stream()
                .filter(StringUtils::hasText)
                .map(this::quoteStringLiteral)
                .reduce((left, right) -> left + "," + right)
                .orElseThrow(() -> new IllegalArgumentException("Milvus 删除缺少 vectorIds"));
        return MilvusVectorStore.DOC_ID_FIELD_NAME + " in [" + joinedIds + "]";
    }

    private String quoteStringLiteral(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private boolean isCollectionMissing(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message != null && message.toLowerCase().contains("collection not found");
    }

    private String resolveDatabaseName() {
        return StringUtils.hasText(ragProperties.milvus().databaseName())
                ? ragProperties.milvus().databaseName()
                : null;
    }

    private String resolveCollectionName() {
        return StringUtils.hasText(ragProperties.milvus().collectionName())
                ? ragProperties.milvus().collectionName()
                : MilvusVectorStore.DEFAULT_COLLECTION_NAME;
    }

    private int resolveEmbeddingDimension() {
        return ragProperties.milvus().embeddingDimension() > 0
                ? ragProperties.milvus().embeddingDimension()
                : MilvusVectorStore.OPENAI_EMBEDDING_DIMENSION_SIZE;
    }
}
