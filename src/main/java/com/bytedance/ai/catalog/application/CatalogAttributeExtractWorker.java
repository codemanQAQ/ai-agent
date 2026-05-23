package com.bytedance.ai.catalog.application;

import com.bytedance.ai.catalog.api.CatalogAttributeExtractRequestedEvent;
import com.bytedance.ai.catalog.persistence.CatalogSpuRecord;
import com.bytedance.ai.catalog.persistence.CatalogSpuRepository;
import com.bytedance.ai.catalog.service.LlmAttributeExtractor;
import com.bytedance.ai.infrastructure.config.RagConcurrencyConfiguration;
import com.bytedance.ai.shared.properties.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * 异步消费 {@link CatalogAttributeExtractRequestedEvent}：把 SPU 描述喂给 LLM，回填 attributes_json。
 *
 * <p>触发条件：{@link TransactionPhase#AFTER_COMMIT} 确保只在导入事务提交后才执行；
 * 执行路径：{@code ragVirtualThreadExecutor}（虚拟线程，每任务一线程）——
 * 项目并发政策禁止固定大小线程池，详见 {@code AGENT.md §3.9}。Doubao RPM=700 的限流由
 * Gateway 层（W4）统一兜底，本 worker 不负责限流。
 *
 * <p>⚠️ 临时形态：本类是 catalog 抽属性切换到 RocketMQ Outbox 之前的桥接实现，
 * 计划在 commit 3「catalog 抽属性切换 RocketMQ Outbox」中删除。
 *
 * <p>状态机：
 * <pre>
 *   PENDING/FAILED ──markRunning──▶ RUNNING ──succeed──▶ DONE
 *                                          ──fail────▶ FAILED（attempt_count++）
 * </pre>
 *
 * <p>如果 {@code rag.catalog.enabled=false}，则直接吞掉事件不做任何工作，便于演示环境跳过 LLM。
 */
@Component
@Deprecated(forRemoval = true)
class CatalogAttributeExtractWorker {

    private static final Logger log = LoggerFactory.getLogger(CatalogAttributeExtractWorker.class);

    private final CatalogSpuRepository spuRepository;
    private final LlmAttributeExtractor llmAttributeExtractor;
    private final RagProperties ragProperties;

    CatalogAttributeExtractWorker(
            CatalogSpuRepository spuRepository,
            LlmAttributeExtractor llmAttributeExtractor,
            RagProperties ragProperties
    ) {
        this.spuRepository = spuRepository;
        this.llmAttributeExtractor = llmAttributeExtractor;
        this.ragProperties = ragProperties;
    }

    @Async(RagConcurrencyConfiguration.RAG_VIRTUAL_THREAD_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAttributeExtractRequested(CatalogAttributeExtractRequestedEvent event) {
        Long spuId = event.spuId();
        if (!ragProperties.catalog().enabled()) {
            log.debug("catalog attribute extraction skipped because feature disabled: spuId={}", spuId);
            return;
        }

        boolean claimed = spuRepository.markAttributeExtractionRunning(spuId);
        if (!claimed) {
            // 已有别的 worker 在跑或状态已经是 DONE/SKIPPED，退出避免重复抽取。
            log.debug("catalog attribute extraction skipped because state not eligible: spuId={}", spuId);
            return;
        }

        CatalogSpuRecord spu = spuRepository.findById(spuId).orElse(null);
        if (spu == null) {
            log.warn("catalog attribute extraction aborted because SPU disappeared: spuId={}", spuId);
            spuRepository.markAttributeExtractionFailed(spuId, "spu_not_found");
            return;
        }

        try {
            Map<String, Object> attributes = llmAttributeExtractor.extract(spu.descriptionMd());
            spuRepository.markAttributeExtractionSucceeded(spuId, attributes);
            log.info(
                    "catalog attribute extraction succeeded: spuId={}, externalRef={}, triggeredBy={}, attributesSize={}",
                    spuId,
                    spu.externalRef(),
                    event.triggeredBy(),
                    attributes.size()
            );
        } catch (RuntimeException exception) {
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            spuRepository.markAttributeExtractionFailed(spuId, reason);
            log.warn(
                    "catalog attribute extraction failed: spuId={}, externalRef={}, triggeredBy={}, reason={}",
                    spuId,
                    spu.externalRef(),
                    event.triggeredBy(),
                    reason
            );
        }
    }
}
