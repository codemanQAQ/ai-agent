package com.involutionhell.backend.rag.indexing;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowCommand;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class RagIndexRecoveryTaskTests {

    private final TransactionTemplate transactionTemplate;

    RagIndexRecoveryTaskTests(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
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


    private record TestDocumentIndexingSpi(List<DocumentIndexingView> failedDocuments) implements DocumentIndexingSpi {

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


    }
}
