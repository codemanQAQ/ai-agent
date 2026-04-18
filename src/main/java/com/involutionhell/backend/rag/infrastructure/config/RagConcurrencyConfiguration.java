package com.involutionhell.backend.rag.infrastructure.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索链路的并发执行配置。
 *
 * <p>统一暴露一个可复用的虚拟线程执行器，供多 query 扩展检索和混合检索扇出复用，
 * 避免每次请求都重复创建和销毁 executor。
 */
@Configuration
public class RagConcurrencyConfiguration {

    @Bean(name = "ragVirtualThreadExecutor", destroyMethod = "close")
    public ExecutorService ragVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
