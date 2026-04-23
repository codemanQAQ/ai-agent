package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.indexing.api.IndexingCommandFacade;
import com.involutionhell.backend.rag.indexing.messaging.RagIndexEventPublisher;
import com.involutionhell.backend.rag.indexing.service.RagIndexingMetrics;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowCommand;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowTriggerType;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;

@Service
class IndexingCommandService implements IndexingCommandFacade {

    private static final Logger log = LoggerFactory.getLogger(IndexingCommandService.class);

    private final ObjectProvider<RagIndexOutboxService> indexOutboxServiceProvider;
    private final RagIndexEventPublisher indexEventPublisher;
    private final IndexWorkflowService indexWorkflowService;
    private final RagIndexingService ragIndexingService;
    private final DocumentIndexingSpi documentIndexingSpi;
    private final RagProperties ragProperties;
    private final Executor ragVirtualThreadExecutor;
    private final RagIndexingMetrics indexingMetrics;

    IndexingCommandService(
            ObjectProvider<RagIndexOutboxService> indexOutboxServiceProvider,
            RagIndexEventPublisher indexEventPublisher,
            IndexWorkflowService indexWorkflowService,
            RagIndexingService ragIndexingService,
            DocumentIndexingSpi documentIndexingSpi,
            RagProperties ragProperties,
            @Qualifier("ragVirtualThreadExecutor") Executor ragVirtualThreadExecutor,
            RagIndexingMetrics indexingMetrics
    ) {
        this.indexOutboxServiceProvider = indexOutboxServiceProvider;
        this.indexEventPublisher = indexEventPublisher;
        this.indexWorkflowService = indexWorkflowService;
        this.ragIndexingService = ragIndexingService;
        this.documentIndexingSpi = documentIndexingSpi;
        this.ragProperties = ragProperties;
        this.ragVirtualThreadExecutor = ragVirtualThreadExecutor;
        this.indexingMetrics = indexingMetrics;
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    @Override
    public void requestIndexing(Long documentId, String contentSha256, String triggeredBy) {
        IndexWorkflowCommand command = IndexWorkflowCommand.of(
                documentId,
                contentSha256,
                IndexWorkflowTriggerType.API,
                triggeredBy
        );

        log.info(
                "RAG index dispatch started: documentId={}, contentSha={}, rocketMqEnabled={}, outboxEnabled={}",
                documentId,
                RagLogHelper.shortSha(contentSha256),
                ragProperties.rocketMq().enabled(),
                ragProperties.outbox().enabled()
        );

        try {
            indexWorkflowService.queue(command);

            if (!isOutboxDispatchMode()) {
                indexWorkflowService.dispatch(command);
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            indexEventPublisher.publish(documentId, contentSha256);
                        }
                    });
                } else {
                    // 兜底逻辑：如果当前因为某种原因没在事务里，直接发
                    indexEventPublisher.publish(documentId, contentSha256);
                }
                return;
            }

            RagIndexOutboxService indexOutboxService = indexOutboxServiceProvider.getIfAvailable();
            if (indexOutboxService == null) {
                throw new IllegalStateException("rag.outbox.enabled=true 但 RagIndexOutboxService 未装配");
            }

            indexOutboxService.enqueue(documentId, contentSha256);
            // 只有确认 Outbox 行已经成功创建后，才把工作流推进到 DISPATCHING，
            // 避免 job/document 先进入 dispatch 状态，但实际尚未具备可投递载体。
            indexWorkflowService.dispatch(command);
        } catch (Exception exception) {
            String errorMessage = "索引任务投递失败: " + exception.getMessage();
            indexWorkflowService.fail(command.withFailure("dispatch", errorMessage));
            throw exception;
        }
    }

    @Override
    public void cleanupPendingIndexing(Long documentId) {
        if (!ragProperties.rocketMq().enabled() || !ragProperties.outbox().enabled()) {
            ragVirtualThreadExecutor.execute(() -> cleanupDirectIndexing(documentId));
            return;
        }

        RagIndexOutboxService outboxService = indexOutboxServiceProvider.getIfAvailable();
        if (outboxService != null) {
            outboxService.deleteByDocumentId(documentId);
        }
    }

    private boolean isOutboxDispatchMode() {
        return ragProperties.rocketMq().enabled() && ragProperties.outbox().enabled();
    }

    private void cleanupDirectIndexing(Long documentId) {
        try {
            ragIndexingService.deleteDocumentIndex(documentId);
            documentIndexingSpi.deleteById(documentId);
            indexingMetrics.recordDeleteCleanup("success");
            log.info("Direct RAG delete cleanup completed: documentId={}", documentId);
        } catch (IllegalArgumentException exception) {
            indexingMetrics.recordDeleteCleanup("skip");
            log.warn(
                    "Direct RAG delete cleanup skipped because document is unavailable: documentId={}, error={}",
                    documentId,
                    RagLogHelper.errorSummary(exception)
            );
        } catch (Exception exception) {
            indexingMetrics.recordDeleteCleanup("failure");
            log.error(
                    "Direct RAG delete cleanup failed: documentId={}, error={}",
                    documentId,
                    RagLogHelper.errorSummary(exception),
                    exception
            );
        }
    }
}
