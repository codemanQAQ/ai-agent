package com.bytedance.ai.infrastructure.config;

import com.bytedance.ai.shared.properties.RagProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * RAG 检索链路与 catalog 异步任务的并发执行配置。
 *
 * <p>统一暴露一个可复用的虚拟线程执行器，供多 query 扩展检索和混合检索扇出复用，
 * 避免每次请求都重复创建和销毁 executor；同时为 catalog 模块的 LLM 属性抽取提供独立
 * 有界线程池，防止 worker 风暴撞 Doubao RPM=700 上限。
 *
 * <p>{@code @EnableAsync} 打开 {@code @Async} 注解支持，主要服务于
 * {@code CatalogAttributeExtractWorker}。
 */
@Configuration
@EnableAsync
public class RagConcurrencyConfiguration {

    public static final String CATALOG_ATTRIBUTE_EXECUTOR = "catalogAttributeExecutor";

    @Bean(name = "ragVirtualThreadExecutor", destroyMethod = "close")
    public ExecutorService ragVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Reactor 侧复用同一组虚拟线程执行 JDBC、Milvus 和 Spring AI 等阻塞调用。
     */
    @Bean(name = "ragBlockingScheduler")
    public Scheduler ragBlockingScheduler(@Qualifier("ragVirtualThreadExecutor") ExecutorService executorService) {
        return Schedulers.fromExecutor(executorService);
    }

    /**
     * catalog 属性抽取专用有界线程池：固定核心并发 + 有界队列 + CallerRuns 拒绝策略，
     * 避免大批量导入时同一时刻把所有 SPU 都打给 LLM，超出 Doubao RPM 限流。
     */
    @Bean(name = CATALOG_ATTRIBUTE_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService catalogAttributeExecutor(RagProperties ragProperties) {
        RagProperties.Catalog catalog = ragProperties.catalog();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                catalog.extractionConcurrency(),
                catalog.extractionConcurrency(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(catalog.extractionQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("catalog-attr-extract-" + thread.threadId());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
