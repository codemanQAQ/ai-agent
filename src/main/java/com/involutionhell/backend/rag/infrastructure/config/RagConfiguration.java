package com.involutionhell.backend.rag.infrastructure.config;

import com.involutionhell.backend.rag.shared.properties.RagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * RAG 模块基础配置。
 *
 * <p>当前主要负责两件事：
 * 1. 注册 {@link RagProperties} 配置属性，供整个 RAG 模块注入使用。
 * 2. 在显式启用 Milvus 时，装配向量存储所需的基础 Bean。
 *
 * <p>这里保留的是 RAG 模块自管的最小装配逻辑，而不是把所有外部组件都集中堆在一个配置类里。
 */
@Configuration
@EnableConfigurationProperties({RagProperties.class})
public class RagConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagConfiguration.class);

    private final RagProperties ragProperties;
    private final ObjectProvider<ChatModel> chatModelProvider;

    public RagConfiguration(RagProperties ragProperties, ObjectProvider<ChatModel> chatModelProvider) {
        this.ragProperties = ragProperties;
        this.chatModelProvider = chatModelProvider;
    }

    @PostConstruct
    void logConfigurationLoaded() {
        log.info(
                "初始化 RAG 基础配置。defaultTopK={}, chunkSize={}, chunkOverlap={}, milvusEnabled={}, rocketMqEnabled={}, outboxEnabled={}, recoveryEnabled={}",
                ragProperties.defaultTopK(),
                ragProperties.chunkSize(),
                ragProperties.chunkOverlap(),
                ragProperties.milvus().enabled(),
                ragProperties.rocketMq().enabled(),
                ragProperties.outbox().enabled(),
                ragProperties.recovery().enabled()
        );
        warnWhenQueryExpansionChatModelMissing();
        warnWhenCompressionChatModelMissing();
        warnWhenRewriteChatModelMissing();
    }

    void warnWhenQueryExpansionChatModelMissing() {
        if (!ragProperties.queryExpansion().enabled() || !ragProperties.queryExpansion().useModel()) {
            return;
        }

        try {
            if (chatModelProvider.getIfAvailable() == null) {
                log.warn("rag.queryExpansion.useModel=true，但当前没有 ChatModel Bean，查询扩展将降级为单查询");
            }
        } catch (Exception exception) {
            log.warn("检测 ChatModel Bean 失败，查询扩展将降级为单查询: {}", exception.getMessage());
        }
    }

    void warnWhenCompressionChatModelMissing() {
        if (!ragProperties.queryTransformation().enabled() || !ragProperties.queryTransformation().useModel()) {
            return;
        }

        try {
            if (chatModelProvider.getIfAvailable() == null) {
                log.warn("rag.queryTransformation.useModel=true，但当前没有 ChatModel Bean，查询压缩将降级为原始问题");
            }
        } catch (Exception exception) {
            log.warn("检测 ChatModel Bean 失败，查询压缩将降级为原始问题: {}", exception.getMessage());
        }
    }

    void warnWhenRewriteChatModelMissing() {
        if (!ragProperties.queryTransformation().rewriteEnabled()
                || !ragProperties.queryTransformation().rewriteUseModel()) {
            return;
        }

        try {
            if (chatModelProvider.getIfAvailable() == null) {
                log.warn("rag.queryTransformation.rewriteUseModel=true，但当前没有 ChatModel Bean，查询 rewrite 将降级为原始问题");
            }
        } catch (Exception exception) {
            log.warn("检测 ChatModel Bean 失败，查询 rewrite 将降级为原始问题: {}", exception.getMessage());
        }
    }

    /**
     * 创建 RAG 使用的 {@link MilvusVectorStore}。
     *
     * <p>仅当 {@code rag.milvus.enabled=true} 时装配。该 Bean 负责：
     * 1. 将 embedding 结果写入 Milvus。
     * 2. 在检索阶段承接 Spring AI 的向量检索调用。
     *
     * <p>这里固定使用当前项目约定的索引类型、距离度量和 batching 策略。
     *
     * @param milvusClient Milvus gRPC 客户端
     * @param embeddingModel embedding 模型
     * @param ragProperties RAG 配置
     * @return 可供 RAG 检索与写入复用的 Milvus 向量存储
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "true")
    public MilvusVectorStore vectorStore(
            MilvusServiceClient milvusClient,
            EmbeddingModel embeddingModel,
            RagProperties ragProperties
    ) {
        MilvusVectorStore.Builder builder = MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName(ragProperties.milvus().collectionName())
                .indexType(IndexType.IVF_FLAT)
                .metricType(MetricType.COSINE)
                .batchingStrategy(new TokenCountBatchingStrategy())
                .initializeSchema(false);

        if (StringUtils.hasText(ragProperties.milvus().databaseName())) {
            builder.databaseName(ragProperties.milvus().databaseName());
        }

        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.query-expansion", name = {"enabled", "use-model"}, havingValue = "true")
    @ConditionalOnBean(ChatModel.class)
    public MultiQueryExpander multiQueryExpander(ChatModel chatModel, RagProperties ragProperties) {
        MultiQueryExpander.Builder builder = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .numberOfQueries(ragProperties.queryExpansion().numberOfQueries())
                .includeOriginal(ragProperties.queryExpansion().includeOriginal());
        String queryTemplate = ragProperties.queryExpansion().queryTemplate();
        if (StringUtils.hasText(queryTemplate)) {
            builder.promptTemplate(new PromptTemplate(queryTemplate));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.query-transformation", name = {"enabled", "use-model"}, havingValue = "true")
    @ConditionalOnBean(ChatModel.class)
    public CompressionQueryTransformer compressionQueryTransformer(ChatModel chatModel, RagProperties ragProperties) {
        CompressionQueryTransformer.Builder builder = CompressionQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel));
        String queryTemplate = ragProperties.queryTransformation().queryTemplate();
        if (StringUtils.hasText(queryTemplate)) {
            builder.promptTemplate(new PromptTemplate(queryTemplate));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.query-transformation", name = {"rewrite-enabled", "rewrite-use-model"}, havingValue = "true")
    @ConditionalOnBean(ChatModel.class)
    public RewriteQueryTransformer rewriteQueryTransformer(ChatModel chatModel, RagProperties ragProperties) {
        RewriteQueryTransformer.Builder builder = RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel));
        String queryTemplate = ragProperties.queryTransformation().rewriteQueryTemplate();
        if (StringUtils.hasText(queryTemplate)) {
            builder.promptTemplate(new PromptTemplate(queryTemplate));
        }
        String targetSearchSystem = ragProperties.queryTransformation().rewriteTargetSearchSystem();
        if (StringUtils.hasText(targetSearchSystem)) {
            builder.targetSearchSystem(targetSearchSystem);
        }
        return builder.build();
    }

    /**
     * 创建 Milvus gRPC 客户端。
     *
     * <p>仅当 {@code rag.milvus.enabled=true} 时装配。该客户端是
     * {@link MilvusVectorStore} 和自定义 Milvus 写入/删除逻辑的底层连接入口。
     *
     * @param ragProperties RAG 配置
     * @return 基于 URI 与 token 建立的 Milvus 客户端
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "true")
    public MilvusServiceClient milvusClient(RagProperties ragProperties) {
        return new MilvusServiceClient(ConnectParam.newBuilder()
                .withToken(ragProperties.milvus().token())
                .withUri(ragProperties.milvus().uri())
                .withConnectTimeout(10, TimeUnit.SECONDS)
                .withKeepAliveTime(100, TimeUnit.SECONDS)
                .build());
    }
}
