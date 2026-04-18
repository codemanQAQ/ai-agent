package com.involutionhell.backend.rag.indexing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性分发待发送的索引 Outbox 事件。
 */
@Component
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "rag.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagIndexOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RagIndexOutboxDispatcher.class);

    private final RagIndexOutboxService outboxService;

    public RagIndexOutboxDispatcher(RagIndexOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Scheduled(fixedDelayString = "${rag.outbox.dispatch-fixed-delay-millis:1000}")
    public void dispatchPending() {
        int dispatched = outboxService.dispatchPendingBatch();
        if (dispatched > 0) {
            log.debug("RAG outbox dispatcher flushed pending events: count={}", dispatched);
        }
    }
}
