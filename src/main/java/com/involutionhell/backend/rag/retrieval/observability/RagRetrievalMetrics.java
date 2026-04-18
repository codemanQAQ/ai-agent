package com.involutionhell.backend.rag.retrieval.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * RAG 检索链路的观测埋点。
 */
@Component
public class RagRetrievalMetrics {

    private final MeterRegistry meterRegistry;

    public RagRetrievalMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public void recordStage(String stage, Duration duration, boolean hasFilter, boolean success, String... extraTags) {
        recordStage(stage, duration, hasFilter, success ? "success" : "error", extraTags);
    }

    public void recordStage(String stage, Duration duration, boolean hasFilter, String outcome, String... extraTags) {
        if (meterRegistry == null) {
            return;
        }
        Timer.Builder builder = Timer.builder("rag.retrieval.stage.duration")
                .tag("stage", stage)
                .tag("outcome", outcome)
                .tag("has_filter", String.valueOf(hasFilter));
        applyExtraTags(builder, extraTags);
        builder.register(meterRegistry).record(duration);
    }

    public void recordExpandedQueryCount(int count) {
        recordSummary("rag.retrieval.expanded_query.count", count);
    }

    public void recordHitCount(String branch, String phase, int count) {
        if (meterRegistry == null || count < 0) {
            return;
        }
        DistributionSummary.builder("rag.retrieval.hit.count")
                .tag("branch", branch)
                .tag("phase", phase)
                .register(meterRegistry)
                .record(count);
    }

    public void recordFallback(String scope, String branch) {
        increment("rag.retrieval.fallback.count", "scope", scope, "branch", branch);
    }

    public void recordFallback(String scope, String branch, String reason) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("rag.retrieval.fallback.count")
                .tag("scope", scope)
                .tag("branch", branch)
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    public void recordRequest(String scope) {
        increment("rag.retrieval.request.count", "scope", scope);
    }

    public void recordZeroHit(String scope) {
        increment("rag.retrieval.zero_hit.count", "scope", scope);
    }

    private void recordSummary(String name, int amount) {
        if (meterRegistry == null || amount < 0) {
            return;
        }
        DistributionSummary.builder(name)
                .register(meterRegistry)
                .record(amount);
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

    private void increment(String name, String firstTagKey, String firstTagValue, String secondTagKey, String secondTagValue) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(name)
                .tag(firstTagKey, firstTagValue)
                .tag(secondTagKey, secondTagValue)
                .register(meterRegistry)
                .increment();
    }

    private void applyExtraTags(Timer.Builder builder, String... extraTags) {
        if (extraTags == null || extraTags.length == 0) {
            return;
        }
        for (int index = 0; index + 1 < extraTags.length; index += 2) {
            builder.tag(extraTags[index], extraTags[index + 1]);
        }
    }
}
