package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.indexing.messaging.RagIndexEventPublisher;
import com.involutionhell.backend.rag.indexing.service.RagIndexingMetrics;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowCommand;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexingCommandServiceTests {

    @Test
    void outboxModeDispatchesOnlyAfterOutboxRowIsCreated() {
        List<String> order = new ArrayList<>();
        TestWorkflowService workflowService = new TestWorkflowService(order);
        TestOutboxService outboxService = new TestOutboxService(order, false);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        IndexingCommandService service = new IndexingCommandService(
                provider(outboxService),
                (documentId, contentSha256) -> {
                    order.add("publish");
                    return "message-" + documentId;
                },
                workflowService,
                null,
                null,
                outboxEnabledProperties(),
                Runnable::run,
                metrics(meterRegistry)
        );

        service.requestIndexing(7L, "sha-7", "test");

        assertThat(order).containsExactly("queue", "enqueue", "dispatch");
        assertThat(workflowService.failedCommands).isEmpty();
    }

    @Test
    void outboxCreationFailureDoesNotAdvanceToDispatching() {
        List<String> order = new ArrayList<>();
        TestWorkflowService workflowService = new TestWorkflowService(order);
        TestOutboxService outboxService = new TestOutboxService(order, true);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        IndexingCommandService service = new IndexingCommandService(
                provider(outboxService),
                (documentId, contentSha256) -> {
                    order.add("publish");
                    return "message-" + documentId;
                },
                workflowService,
                null,
                null,
                outboxEnabledProperties(),
                Runnable::run,
                metrics(meterRegistry)
        );

        assertThatThrownBy(() -> service.requestIndexing(9L, "sha-9", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox unavailable");

        assertThat(order).containsExactly("queue", "enqueue", "fail");
        assertThat(workflowService.dispatchedCommands).isEmpty();
        assertThat(workflowService.failedCommands).hasSize(1);
    }

    @Test
    void directCleanupRecordsSuccessMetric() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        IndexingCommandService service = new IndexingCommandService(
                provider(null),
                (documentId, contentSha256) -> "message-" + documentId,
                new TestWorkflowService(new ArrayList<>()),
                new TestRagIndexingService(false),
                new TestDocumentIndexingSpi(false),
                RagProperties.defaults(),
                Runnable::run,
                metrics(meterRegistry)
        );

        service.cleanupPendingIndexing(21L);

        assertThat(counterValue(meterRegistry, "rag.indexing.delete.cleanup.count", "outcome", "success")).isEqualTo(1.0d);
    }

    @Test
    void directCleanupRecordsFailureMetric() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        IndexingCommandService service = new IndexingCommandService(
                provider(null),
                (documentId, contentSha256) -> "message-" + documentId,
                new TestWorkflowService(new ArrayList<>()),
                new TestRagIndexingService(true),
                new TestDocumentIndexingSpi(false),
                RagProperties.defaults(),
                Runnable::run,
                metrics(meterRegistry)
        );

        service.cleanupPendingIndexing(22L);

        assertThat(counterValue(meterRegistry, "rag.indexing.delete.cleanup.count", "outcome", "failure")).isEqualTo(1.0d);
    }

    private static RagIndexingMetrics metrics(SimpleMeterRegistry meterRegistry) {
        return new RagIndexingMetrics(provider(meterRegistry));
    }

    private static double counterValue(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter() == null
                ? 0.0d
                : meterRegistry.find(name).tags(tags).counter().count();
    }

    private RagProperties outboxEnabledProperties() {
        RagProperties defaults = RagProperties.defaults();
        return new RagProperties(
                defaults.defaultTopK(),
                defaults.chunkSize(),
                defaults.chunkOverlap(),
                defaults.embeddingModel(),
                new RagProperties.RocketMq(
                        true,
                        "localhost:8081",
                        "rag-index-topic",
                        defaults.rocketMq().tag(),
                        defaults.rocketMq().consumerGroup(),
                        defaults.rocketMq().requestTimeoutSeconds(),
                        defaults.rocketMq().sslEnabled(),
                        defaults.rocketMq().parseFailureAlertThreshold(),
                        defaults.rocketMq().parseFailurePayloadPreviewLength()
                ),
                defaults.milvus(),
                defaults.indexing(),
                defaults.queryTransformation(),
                defaults.queryExpansion(),
                defaults.retrieval(),
                new RagProperties.Outbox(
                        true,
                        defaults.outbox().dispatchFixedDelayMillis(),
                        defaults.outbox().batchSize(),
                        defaults.outbox().retryBackoffMillis(),
                        defaults.outbox().sendingStaleMillis()
                ),
                defaults.recovery()
        );
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private static final class TestWorkflowService extends IndexWorkflowService {

        private final List<String> order;
        private final List<IndexWorkflowCommand> dispatchedCommands = new ArrayList<>();
        private final List<IndexWorkflowCommand> failedCommands = new ArrayList<>();

        private TestWorkflowService(List<String> order) {
            super(null, null, null, null, null, null);
            this.order = order;
        }

        @Override
        public void queue(IndexWorkflowCommand command) {
            order.add("queue");
        }

        @Override
        public void dispatch(IndexWorkflowCommand command) {
            order.add("dispatch");
            dispatchedCommands.add(command);
        }

        @Override
        public void fail(IndexWorkflowCommand command) {
            order.add("fail");
            failedCommands.add(command);
        }
    }

    private static final class TestOutboxService extends RagIndexOutboxService {

        private final List<String> order;
        private final boolean fail;

        private TestOutboxService(List<String> order, boolean fail) {
            super(RagProperties.defaults(), null, null, new TestWorkflowService(new ArrayList<>()), metrics(new SimpleMeterRegistry()));
            this.order = order;
            this.fail = fail;
        }

        @Override
        public void enqueue(Long documentId, String contentSha256) {
            order.add("enqueue");
            if (fail) {
                throw new IllegalStateException("outbox unavailable");
            }
        }
    }

    private static final class TestDocumentIndexingSpi implements com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi {

        private final boolean throwOnDelete;

        private TestDocumentIndexingSpi(boolean throwOnDelete) {
            this.throwOnDelete = throwOnDelete;
        }

        @Override
        public java.util.Optional<com.involutionhell.backend.rag.document.spi.DocumentIndexingView> findById(Long id) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.List<com.involutionhell.backend.rag.document.spi.DocumentIndexingView> findPendingBefore(java.time.OffsetDateTime cutoff, int limit) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<com.involutionhell.backend.rag.document.spi.DocumentIndexingView> findProcessingBefore(java.time.OffsetDateTime cutoff, int limit) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<com.involutionhell.backend.rag.document.spi.DocumentIndexingView> findFailedBefore(java.time.OffsetDateTime cutoff, int limit) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<com.involutionhell.backend.rag.document.spi.DocumentIndexingView> findDeletingBefore(java.time.OffsetDateTime cutoff, int limit) {
            return java.util.List.of();
        }

        @Override
        public void markPending(Long id) {
        }

        @Override
        public void requeue(Long id, String note) {
        }

        @Override
        public void markProcessing(Long id) {
        }

        @Override
        public void markIndexed(Long id, Long indexedGeneration, int chunkCount, java.time.OffsetDateTime indexedAt) {
        }

        @Override
        public void markFailed(Long id, String errorMessage) {
        }

        @Override
        public void markDeleting(Long id, String note) {
        }

        @Override
        public void deleteById(Long id) {
            if (throwOnDelete) {
                throw new IllegalArgumentException("missing document");
            }
        }
    }

    private static final class TestRagIndexingService extends RagIndexingService {

        private final boolean throwOnDelete;

        private TestRagIndexingService(boolean throwOnDelete) {
            super(null, null, null, null, null, null, null, null, null, null, RagProperties.defaults(), null, metrics(new SimpleMeterRegistry()), null);
            this.throwOnDelete = throwOnDelete;
        }

        @Override
        public void deleteDocumentIndex(Long documentId) {
            if (throwOnDelete) {
                throw new IllegalStateException("cleanup failed");
            }
        }
    }
}
