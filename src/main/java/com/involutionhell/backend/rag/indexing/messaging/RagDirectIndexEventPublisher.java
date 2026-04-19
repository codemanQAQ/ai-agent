package com.involutionhell.backend.rag.indexing.messaging;

import com.involutionhell.backend.rag.indexing.application.RagIndexingService;
import com.involutionhell.backend.rag.indexing.model.RagIndexAttemptException;
import com.involutionhell.backend.rag.indexing.model.RagIndexFailure;
import com.involutionhell.backend.rag.indexing.service.RagIndexingFailureClassifier;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowCommand;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowTriggerType;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 未启用 RocketMQ 时，直接在当前进程执行索引。
 */
@Service
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class RagDirectIndexEventPublisher implements RagIndexEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RagDirectIndexEventPublisher.class);

    private final RagIndexingService ragIndexingService;
    private final IndexWorkflowService workflowService;
    private final RagIndexingFailureClassifier failureClassifier;
    private final Executor ragVirtualThreadExecutor;

    public RagDirectIndexEventPublisher(
            RagIndexingService ragIndexingService,
            IndexWorkflowService workflowService,
            RagIndexingFailureClassifier failureClassifier,
            @Qualifier("ragVirtualThreadExecutor") Executor ragVirtualThreadExecutor
    ) {
        this.ragIndexingService = ragIndexingService;
        this.workflowService = workflowService;
        this.failureClassifier = failureClassifier;
        this.ragVirtualThreadExecutor = ragVirtualThreadExecutor;
    }

    @Override
    public String publish(Long documentId, String contentSha256) {
        IndexWorkflowCommand command = IndexWorkflowCommand.of(
                documentId,
                contentSha256,
                IndexWorkflowTriggerType.API,
                "direct-publisher"
        );
        log.info(
                "RocketMQ is disabled, scheduling in-process RAG indexing: documentId={}, contentSha={}",
                documentId,
                RagLogHelper.shortSha(contentSha256)
        );

        String publishId = "direct-" + documentId + "-" + RagLogHelper.shortSha(contentSha256);

        try {
            ragVirtualThreadExecutor.execute(() -> executeIndexing(documentId, contentSha256, command));
            return publishId;
        } catch (RejectedExecutionException exception) {
            String reason = "executor_rejected";
            String errorMessage = "Direct RAG indexing task was rejected by executor: "
                    + abbreviate(exception.getMessage());

            failDirectIndexing(command, reason, errorMessage);

            log.error(
                    "Direct RAG indexing submission was rejected: documentId={}, contentSha={}, publishId={}, error={}",
                    documentId,
                    RagLogHelper.shortSha(contentSha256),
                    publishId,
                    RagLogHelper.errorSummary(exception),
                    exception
            );

            throw new IllegalStateException(
                    "Failed to schedule direct RAG indexing task: executor rejected submission",
                    exception
            );
        }
    }

    private void executeIndexing(Long documentId, String contentSha256, IndexWorkflowCommand command) {
        try {
            ragIndexingService.indexDocument(documentId, contentSha256, command);
        } catch (IllegalArgumentException exception) {
            ragIndexingService.deleteOrphanedIndexingState(documentId);
            log.warn(
                    "Direct RAG indexing skipped because document is unavailable: documentId={}, contentSha={}, error={}",
                    documentId,
                    RagLogHelper.shortSha(contentSha256),
                    RagLogHelper.errorSummary(exception)
            );
        } catch (RagIndexAttemptException exception) {
            if (isMissingDocument(exception)) {
                ragIndexingService.deleteOrphanedIndexingState(documentId);
                log.info(
                        "Direct RAG indexing cancelled because document disappeared during execution: documentId={}, contentSha={}, stage={}",
                        documentId,
                        RagLogHelper.shortSha(contentSha256),
                        exception.getStage()
                );
            } else {
                failDirectIndexing(command, exception.getReason(), exception.getErrorMessage());
                log.error(
                        "Direct RAG indexing failed: documentId={}, contentSha={}, stage={}, reason={}, retryable={}",
                        documentId,
                        RagLogHelper.shortSha(contentSha256),
                        exception.getStage(),
                        exception.getReason(),
                        exception.isRetryable(),
                        exception
                );
            }
        } catch (Exception exception) {
            RagIndexFailure failure = failureClassifier.classify(exception);
            String errorMessage = "索引失败 [" + failure.reason() + "]: " + abbreviate(exception.getMessage());
            failDirectIndexing(command, failure.reason(), errorMessage);
            log.error(
                    "Direct RAG indexing failed unexpectedly: documentId={}, contentSha={}, reason={}",
                    documentId,
                    RagLogHelper.shortSha(contentSha256),
                    failure.reason(),
                    exception
            );
        }
    }

    private void failDirectIndexing(IndexWorkflowCommand command, String reason, String errorMessage) {
        try {
            workflowService.fail(command.withFailure(reason, errorMessage));
        } catch (Exception exception) {
            log.warn(
                    "Direct RAG indexing could not transition to FAILED; leaving terminal handling to logs: documentId={}, contentSha={}, reason={}, transitionError={}",
                    command.documentId(),
                    RagLogHelper.shortSha(command.contentSha256()),
                    reason,
                    RagLogHelper.errorSummary(exception)
            );
        }
    }

    private boolean isMissingDocument(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IllegalArgumentException illegalArgumentException
                    && illegalArgumentException.getMessage() != null
                    && illegalArgumentException.getMessage().contains("RAG 文档不存在")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String abbreviate(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        if (message.length() <= 240) {
            return message;
        }
        return message.substring(0, 237) + "...";
    }
}
