package com.involutionhell.backend.rag.infrastructure.scheduling;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAG 定时调度配置。
 *
 * <p>用于开启模块内的 {@code @Scheduled} 能力，当前主要服务于：
 * 1. Outbox 分发任务
 * 2. 离线索引补偿恢复任务
 *
 * <p>这里本身不声明具体任务，只负责打开调度基础设施。
 */
@Configuration
@EnableScheduling
public class RagSchedulingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagSchedulingConfiguration.class);

    @PostConstruct
    void logSchedulingEnabled() {
        log.info("启用 RAG 定时调度能力，Outbox 分发与补偿恢复任务可参与启动。");
    }
}
