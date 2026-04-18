package com.involutionhell.backend.rag.indexing.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * RAG 索引链路的观测埋点。
 */
@Component
public class RagIndexingMetrics {

    private final MeterRegistry meterRegistry;

    public RagIndexingMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public void recordIndexSuccess(int chunkCount, Duration duration) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder("rag.indexing.duration")
                .tag("outcome", "success")
                .register(meterRegistry)
                .record(duration);
        DistributionSummary.builder("rag.indexing.chunk.count")
                .register(meterRegistry)
                .record(chunkCount);
    }

    public void recordMilvusWrite(int chunkCount, Duration duration, boolean cacheEnabled) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder("rag.indexing.milvus.write.duration")
                .tag("cache", String.valueOf(cacheEnabled))
                .register(meterRegistry)
                .record(duration);
        DistributionSummary.builder("rag.indexing.milvus.write.chunks")
                .register(meterRegistry)
                .record(chunkCount);
    }

    public void recordRetry(String reason) {
        increment("rag.indexing.retry.count", "reason", reason);
    }

    public void recordFailure(String reason, boolean retryable) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("rag.indexing.failure.count")
                .tag("reason", reason)
                .tag("retryable", String.valueOf(retryable))
                .register(meterRegistry)
                .increment();
    }

    public void recordMessageParseFailure(boolean terminal) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("rag.indexing.message.parse_failure.count")
                .tag("terminal", String.valueOf(terminal))
                .register(meterRegistry)
                .increment();
    }

    public void recordOutboxDispatchSuccess() {
        increment("rag.indexing.outbox.dispatch.count", "outcome", "success");
    }

    public void recordOutboxDispatchFailure(String phase) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("rag.indexing.outbox.dispatch.count")
                .tag("outcome", "failure")
                .tag("phase", phase == null || phase.isBlank() ? "unknown" : phase)
                .register(meterRegistry)
                .increment();
    }

    public void recordRecoveryScan(String category, int count) {
        if (meterRegistry == null || count <= 0) {
            return;
        }
        Counter.builder("rag.indexing.recovery.scan.count")
                .tag("category", normalizeTag(category))
                .register(meterRegistry)
                .increment(count);
    }

    public void recordRecoveryOutcome(String category, String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("rag.indexing.recovery.outcome.count")
                .tag("category", normalizeTag(category))
                .tag("outcome", normalizeTag(outcome))
                .register(meterRegistry)
                .increment();
    }

    public void recordDeleteCleanup(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("rag.indexing.delete.cleanup.count")
                .tag("outcome", normalizeTag(outcome))
                .register(meterRegistry)
                .increment();
    }

    public void recordCacheHits(int count) {
        increment("rag.embedding.cache.hit.count", count);
    }

    public void recordCacheMisses(int count) {
        increment("rag.embedding.cache.miss.count", count);
    }

    private void increment(String name, int amount) {
        if (meterRegistry == null || amount <= 0) {
            return;
        }
        Counter.builder(name)
                .register(meterRegistry)
                .increment(amount);
    }

    private void increment(String name, String tagKey, String tagValue) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(name)
                .tag(tagKey, tagValue)
                .register(meterRegistry)
                .increment();
    }

    private String normalizeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
