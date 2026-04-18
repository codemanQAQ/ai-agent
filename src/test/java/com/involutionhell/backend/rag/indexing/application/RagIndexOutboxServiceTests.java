package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.indexing.messaging.RagIndexEventPublisher;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRecord;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRepository;
import com.involutionhell.backend.rag.indexing.service.RagIndexingMetrics;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowCommand;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

class RagIndexOutboxServiceTests {

    @Test
    void dispatchFailureMarksOutboxFailedAndRequestsWorkflowRetry() {
        TestOutboxRepository outboxRepository = new TestOutboxRepository();
        TestWorkflowService workflowService = new TestWorkflowService();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RagIndexOutboxService service = new RagIndexOutboxService(
                RagProperties.defaults(),
                outboxRepository,
                (documentId, contentSha256) -> {
                    throw new IllegalStateException("mq unavailable");
                },
                workflowService,
                metrics(meterRegistry)
        );

        int dispatched = service.dispatchPendingBatch();

        assertThat(dispatched).isZero();
        assertThat(outboxRepository.markedFailed).isTrue();
        assertThat(workflowService.retryCommands).hasSize(1);
        IndexWorkflowCommand command = workflowService.retryCommands.getFirst();
        assertThat(command.documentId()).isEqualTo(7L);
        assertThat(command.contentSha256()).isEqualTo("sha-7");
        assertThat(command.failureReason()).isEqualTo("dispatch");
        assertThat(command.note()).contains("Outbox dispatch failed");
        assertThat(counterValue(meterRegistry, "rag.indexing.outbox.dispatch.count", "outcome", "failure", "phase", "publish"))
                .isEqualTo(1.0d);
    }

    @Test
    void markSentFailureDoesNotImmediatelyMarkOutboxFailed() {
        TestOutboxRepository outboxRepository = new TestOutboxRepository();
        outboxRepository.throwOnMarkSent = true;
        TestWorkflowService workflowService = new TestWorkflowService();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RagIndexOutboxService service = new RagIndexOutboxService(
                RagProperties.defaults(),
                outboxRepository,
                (documentId, contentSha256) -> {
                    return "message-" + documentId;
                },
                workflowService,
                metrics(meterRegistry)
        );

        int dispatched = service.dispatchPendingBatch();

        assertThat(dispatched).isZero();
        assertThat(outboxRepository.markedFailed).isFalse();
        assertThat(workflowService.retryCommands).isEmpty();
        assertThat(outboxRepository.markedSent).isFalse();
        assertThat(counterValue(meterRegistry, "rag.indexing.outbox.dispatch.count", "outcome", "failure", "phase", "sent_confirmation"))
                .isEqualTo(1.0d);
    }

    @Test
    void stuckSendingEventWithObservedDeliveryIsMarkedSentInsteadOfRetried() {
        TestOutboxRepository outboxRepository = new TestOutboxRepository();
        outboxRepository.dispatchableEvents = List.of();
        outboxRepository.stuckEvents = List.of(outboxRepository.event);
        outboxRepository.currentEvent = new RagIndexOutboxRecord(
                11L,
                7L,
                "sha-7",
                "INDEX_DOCUMENT",
                "SENT",
                0,
                "message-id-1",
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        TestWorkflowService workflowService = new TestWorkflowService();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RagIndexOutboxService service = new RagIndexOutboxService(
                RagProperties.defaults(),
                outboxRepository,
                (documentId, contentSha256) -> {
                    return "message-" + documentId;
                },
                workflowService,
                metrics(meterRegistry)
        );

        int resetCount = service.resetStuckSendingEvents();

        assertThat(resetCount).isEqualTo(1);
        assertThat(outboxRepository.markedSent).isFalse();
        assertThat(outboxRepository.resetForRetryCalled).isFalse();
        assertThat(counterValue(meterRegistry, "rag.indexing.outbox.dispatch.count", "outcome", "success"))
                .isZero();
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

    private static final class TestOutboxRepository implements RagIndexOutboxRepository {

        private final RagIndexOutboxRecord event = new RagIndexOutboxRecord(
                11L,
                7L,
                "sha-7",
                "INDEX_DOCUMENT",
                "NEW",
                0,
                null,
                null,
                OffsetDateTime.now(),
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        private List<RagIndexOutboxRecord> dispatchableEvents = List.of(event);
        private List<RagIndexOutboxRecord> stuckEvents = List.of();
        private RagIndexOutboxRecord currentEvent = event;
        private boolean markedFailed;
        private boolean markedSent;
        private boolean throwOnMarkSent;
        private boolean resetForRetryCalled;

        @Override
        public void enqueue(Long documentId, String contentSha256, com.involutionhell.backend.rag.indexing.model.RagIndexOutboxEventType eventType) {
        }

        @Override
        public List<RagIndexOutboxRecord> findDispatchable(OffsetDateTime now, int limit) {
            return dispatchableEvents;
        }

        @Override
        public boolean markSending(Long id) {
            return true;
        }

        @Override
        public void markSent(Long id, String messageId) {
            if (throwOnMarkSent) {
                throw new IllegalStateException("db down after publish");
            }
            this.markedSent = true;
        }

        @Override
        public void markFailed(Long id, String errorMessage, OffsetDateTime nextAttemptAt) {
            this.markedFailed = true;
        }

        @Override
        public boolean confirmConsumed(Long documentId, String contentSha256, String messageId) {
            return false;
        }

        @Override
        public boolean confirmConsumedByMessageId(String messageId) {
            return false;
        }

        @Override
        public List<RagIndexOutboxRecord> findStuckSendingBefore(OffsetDateTime cutoff, int limit) {
            return stuckEvents;
        }

        @Override
        public void resetForRetry(Long id, String errorMessage, OffsetDateTime nextAttemptAt) {
            this.resetForRetryCalled = true;
        }

        @Override
        public Optional<RagIndexOutboxRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256) {
            return Optional.ofNullable(currentEvent);
        }

        @Override
        public void deleteByDocumentId(Long documentId) {
        }
    }

    private static final class TestWorkflowService extends IndexWorkflowService {

        private final List<IndexWorkflowCommand> retryCommands = new ArrayList<>();

        private TestWorkflowService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public void retry(IndexWorkflowCommand command) {
            retryCommands.add(command);
        }
    }

}
