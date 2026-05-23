package com.bytedance.ai.infrastructure.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * RAG 链路与跨模块异步任务的并发执行配置。
 *
 * <p>项目并发政策（详见 {@code AGENT.md §3.9}）要求所有同进程异步执行最终都落到
 * 虚拟线程上，不允许新增固定大小线程池。本类是该政策的唯一集中点：
 * <ul>
 *   <li>{@link #ragVirtualThreadExecutor()} —— {@code @Async} 与显式 {@code submit}
 *       共用的虚拟线程执行器（per-task-per-thread）。</li>
 *   <li>{@link #ragBlockingScheduler(ExecutorService)} —— Reactor 侧的 Scheduler 包装，
 *       承载 JDBC / Milvus / Spring AI 等阻塞调用。</li>
 * </ul>
 *
 * <p>{@code @EnableAsync} 打开 {@code @Async} 注解支持。任何 {@code @Async} 必须显式
 * 指定 {@code "ragVirtualThreadExecutor"} 作为 qualifier，避免 Spring 默认走平台线程池。
 *
 * <p>历史：第一版曾为 catalog 抽属性新增固定大小的 {@code catalogAttributeExecutor}，
 * 与项目并发政策冲突，已拆除；catalog 抽属性短期通过 {@code ragVirtualThreadExecutor}
 * 桥接，最终方案见 {@code rag.catalog.rocket-mq-topic} 走 RocketMQ Outbox。
 */
@Configuration
@EnableAsync
public class RagConcurrencyConfiguration {

    public static final String RAG_VIRTUAL_THREAD_EXECUTOR = "ragVirtualThreadExecutor";
    public static final String RAG_BLOCKING_SCHEDULER = "ragBlockingScheduler";

    @Bean(name = RAG_VIRTUAL_THREAD_EXECUTOR, destroyMethod = "close")
    public ExecutorService ragVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Reactor 侧复用同一组虚拟线程执行 JDBC、Milvus 和 Spring AI 等阻塞调用。
     */
    @Bean(name = RAG_BLOCKING_SCHEDULER)
    public Scheduler ragBlockingScheduler(@Qualifier(RAG_VIRTUAL_THREAD_EXECUTOR) ExecutorService executorService) {
        return Schedulers.fromExecutor(executorService);
    }
}
