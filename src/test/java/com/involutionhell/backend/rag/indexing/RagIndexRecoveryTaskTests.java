package com.involutionhell.backend.rag.indexing;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import com.involutionhell.backend.rag.indexing.application.RagIndexOutboxService;
import com.involutionhell.backend.rag.indexing.application.RagIndexRecoveryTask;
import com.involutionhell.backend.rag.indexing.application.RagIndexingService;
import com.involutionhell.backend.rag.indexing.model.RagIndexJobStatus;
import com.involutionhell.backend.rag.indexing.model.RagIndexStage;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRecord;
import com.involutionhell.backend.rag.indexing.service.RagIndexingMetrics;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowCommand;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowTriggerType;
import com.involutionhell.backend.rag.shared.model.RagDocumentStatus;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

class RagIndexRecoveryTaskTests {

    @Test
    void requeueFailureDoesNotStopRemainingDocuments() {
        DocumentIndexingView first = staleDocument(1L, "sha-1");
        DocumentIndexingView second = staleDocument(2L, "sha-2");

        TestDocumentIndexingSpi documentSpi = new TestDocumentIndexingSpi(List.of(first, second));
        TestWorkflowService workflowService = new TestWorkflowService(1L);
        TestOutboxService outboxService = new TestOutboxService();
        TestIndexingService indexingService = new TestIndexingService();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        RagIndexRecoveryTask task = new RagIndexRecoveryTask(
                RagProperties.defaults(),
                documentSpi,
                new TestJobRepository(null),
                workflowService,
                outboxService,
                indexingService,
                metrics(meterRegistry)
        );

        task.recoverStaleDocuments();

        assertThat(workflowService.queuedDocumentIds).containsExactly(1L, 2L);
        assertThat(outboxService.enqueuedDocumentIds).containsExactly(2L);
        assertThat(workflowService.skippedCommands).hasSize(1);
        assertThat(workflowService.skippedCommands.getFirst().documentId()).isEqualTo(1L);
        assertThat(workflowService.skippedCommands.getFirst().failureReason()).isEqualTo("recovery");
        assertThat(workflowService.skippedCommands.getFirst().note()).contains("恢复任务重新投递失败");
        assertThat(counterValue(meterRegistry, "rag.indexing.recovery.scan.count", "category", "failed")).isEqualTo(2.0d);
        assertThat(counterValue(meterRegistry, "rag.indexing.recovery.outcome.count", "category", "failed", "outcome", "failure")).isEqualTo(1.0d);
        assertThat(counterValue(meterRegistry, "rag.indexing.recovery.outcome.count", "category", "failed", "outcome", "success")).isEqualTo(1.0d);
    }

    @Test
    void terminalJobStatePreventsRedundantRecovery() {
        DocumentIndexingView document = staleDocument(3L, "sha-3");
        TestDocumentIndexingSpi documentSpi = new TestDocumentIndexingSpi(List.of(document));
        TestWorkflowService workflowService = new TestWorkflowService(-1L);
        TestOutboxService outboxService = new TestOutboxService();
        TestIndexingService indexingService = new TestIndexingService();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        RagIndexRecoveryTask task = new RagIndexRecoveryTask(
                RagProperties.defaults(),
                documentSpi,
                new TestJobRepository(new RagIndexJobRecord(
                        10L,
                        3L,
                        "sha-3",
                        RagIndexJobStatus.SUCCEEDED.name(),
                        RagIndexStage.COMPLETED.name(),
                        1L,
                        "SUCCEED",
                        1,
                        100L,
                        "message-3",
                        null,
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        OffsetDateTime.now().minusHours(2),
                        OffsetDateTime.now()
                )),
                workflowService,
                outboxService,
                indexingService,
                metrics(meterRegistry)
        );

        task.recoverStaleDocuments();

        assertThat(workflowService.queuedDocumentIds).isEmpty();
        assertThat(outboxService.enqueuedDocumentIds).isEmpty();
        assertThat(counterValue(meterRegistry, "rag.indexing.recovery.scan.count", "category", "failed")).isEqualTo(1.0d);
        assertThat(counterValue(meterRegistry, "rag.indexing.recovery.outcome.count", "category", "failed", "outcome", "skip")).isEqualTo(1.0d);
    }

    private static RagIndexingMetrics metrics(SimpleMeterRegistry meterRegistry) {
        return new RagIndexingMetrics(provider(meterRegistry));
    }

    private static double counterValue(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter() == null
                ? 0.0d
                : meterRegistry.find(name).tags(tags).counter().count();
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

    private DocumentIndexingView staleDocument(Long id, String sha) {
        OffsetDateTime now = OffsetDateTime.now().minusHours(2);
        return new DocumentIndexingView(
                id,
                "markdown",
                "docs/" + id + ".md",
                "doc-" + id,
                "Doc " + id,
                "# Title\n\nContent",
                sha,
                null,
                RagDocumentStatus.FAILED.name(),
                0,
                0,
                "{}",
                "failed",
                now,
                null,
                now,
                now
        );
    }

    private static final class TestDocumentIndexingSpi implements DocumentIndexingSpi {

        private final List<DocumentIndexingView> failedDocuments;

        private TestDocumentIndexingSpi(List<DocumentIndexingView> failedDocuments) {
            this.failedDocuments = failedDocuments;
        }

        @Override
        public Optional<DocumentIndexingView> findById(Long id) {
            return failedDocuments.stream().filter(document -> document.id().equals(id)).findFirst();
        }

        @Override
        public List<DocumentIndexingView> findPendingBefore(OffsetDateTime cutoff, int limit) {
            return List.of();
        }

        @Override
        public List<DocumentIndexingView> findProcessingBefore(OffsetDateTime cutoff, int limit) {
            return List.of();
        }

        @Override
        public List<DocumentIndexingView> findFailedBefore(OffsetDateTime cutoff, int limit) {
            return failedDocuments;
        }

        @Override
        public List<DocumentIndexingView> findDeletingBefore(OffsetDateTime cutoff, int limit) {
            return List.of();
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
        public void markIndexed(Long id, Long indexedGeneration, int chunkCount, OffsetDateTime indexedAt) {
        }

        @Override
        public void markFailed(Long id, String errorMessage) {
        }

        @Override
        public void markDeleting(Long id, String note) {
        }

        @Override
        public void deleteById(Long id) {
        }
    }

    private static final class TestWorkflowService extends IndexWorkflowService {

        private final Long failingDocumentId;
        private final List<Long> queuedDocumentIds = new ArrayList<>();
        private final List<IndexWorkflowCommand> skippedCommands = new ArrayList<>();

        private TestWorkflowService(Long failingDocumentId) {
            super(null, null, null, null, null, null);
            this.failingDocumentId = failingDocumentId;
        }

        @Override
        public void queue(IndexWorkflowCommand command) {
            queuedDocumentIds.add(command.documentId());
            if (failingDocumentId.equals(command.documentId())) {
                throw new IllegalStateException("queue failed");
            }
        }

        @Override
        public void dispatch(IndexWorkflowCommand command) {
        }

        @Override
        public void skip(IndexWorkflowCommand command) {
            skippedCommands.add(command);
        }
    }

    private static final class TestOutboxService extends RagIndexOutboxService {

        private final List<Long> enqueuedDocumentIds = new ArrayList<>();

        private TestOutboxService() {
            super(RagProperties.defaults(), null, null, new TestWorkflowService(-1L), metrics(new SimpleMeterRegistry()));
        }

        @Override
        public void enqueue(Long documentId, String contentSha256) {
            enqueuedDocumentIds.add(documentId);
        }

        @Override
        public Optional<RagIndexOutboxRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256) {
            return Optional.empty();
        }

        @Override
        public int resetStuckSendingEvents() {
            return 0;
        }
    }

    private static final class TestIndexingService extends RagIndexingService {

        private TestIndexingService() {
            super(null, null, null, null, null, null, null, null, null, null, RagProperties.defaults(), null, null, null);
        }

        @Override
        public void deleteDocumentIndex(Long documentId) {
        }
    }

    private record TestJobRepository(RagIndexJobRecord job)
            implements com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository {

        @Override
        public void queue(Long documentId, String contentSha256) {
        }

        @Override
        public void attachMessageId(Long documentId, String contentSha256, String messageId) {
        }

        @Override
        public void annotateEvent(Long documentId, String contentSha256, String event) {
        }

        @Override
        public void startAttempt(Long documentId, String contentSha256, Long targetGeneration) {
        }

        @Override
        public void updateStage(Long documentId, String contentSha256, com.involutionhell.backend.rag.indexing.model.RagIndexStage stage) {
        }

        @Override
        public void recordRetry(Long documentId, String contentSha256, com.involutionhell.backend.rag.indexing.model.RagIndexStage stage, String errorMessage) {
        }

        @Override
        public void markSucceeded(Long documentId, String contentSha256, Long targetGeneration) {
        }

        @Override
        public void markFailed(Long documentId, String contentSha256, com.involutionhell.backend.rag.indexing.model.RagIndexStage stage, String errorMessage) {
        }

        @Override
        public void markSkipped(Long documentId, String contentSha256, String reason) {
        }

        @Override
        public Optional<RagIndexJobRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256) {
            return Optional.ofNullable(job);
        }

        @Override
        public void deleteByDocumentId(Long documentId) {
        }
    }
}
