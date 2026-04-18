package com.involutionhell.backend.rag.indexing.workflow;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository;
import com.involutionhell.backend.rag.indexing.model.RagIndexStage;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/**
 * 把工作流状态投影到 rag_documents 与 rag_index_jobs。
 */
@Component
public class IndexWorkflowProjector {

    private final DocumentIndexingSpi documentIndexingSpi;
    private final RagIndexJobRepository jobRepository;

    public IndexWorkflowProjector(
            DocumentIndexingSpi documentIndexingSpi,
            RagIndexJobRepository jobRepository
    ) {
        this.documentIndexingSpi = documentIndexingSpi;
        this.jobRepository = jobRepository;
    }

    /**
     * 将状态机跃迁结果写回现有业务表。
     */
    public void project(
            IndexWorkflowState fromState,
            IndexWorkflowState toState,
            IndexWorkflowEvent event,
            IndexWorkflowCommand command
    ) {
        Long documentId = command.documentId();
        String contentSha256 = command.contentSha256();
        switch (toState) {
            case QUEUED -> {
                jobRepository.queue(documentId, contentSha256);
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
                if (command.triggerType() == IndexWorkflowTriggerType.RECOVERY && command.note() != null) {
                    documentIndexingSpi.requeue(documentId, command.note());
                } else {
                    documentIndexingSpi.markPending(documentId);
                }
            }
            case DISPATCHING -> {
                jobRepository.updateStage(documentId, contentSha256, RagIndexStage.DISPATCHING);
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case PREPARING -> {
                jobRepository.startAttempt(documentId, contentSha256, command.targetGeneration());
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
                documentIndexingSpi.markProcessing(documentId);
            }
            case CHUNKING -> {
                jobRepository.updateStage(documentId, contentSha256, RagIndexStage.CHUNKING);
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case SAVE_CHUNKS -> {
                jobRepository.updateStage(documentId, contentSha256, RagIndexStage.SAVE_CHUNKS);
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case VECTOR_INDEXING -> {
                jobRepository.updateStage(documentId, contentSha256, RagIndexStage.VECTOR_INDEXING);
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case COMMIT_INDEX -> {
                jobRepository.updateStage(documentId, contentSha256, RagIndexStage.COMMIT_INDEX);
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case COMPLETED -> {
                jobRepository.markSucceeded(documentId, contentSha256, command.targetGeneration());
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
                documentIndexingSpi.markIndexed(
                        documentId,
                        command.targetGeneration(),
                        command.chunkCount() == null ? 0 : command.chunkCount(),
                        OffsetDateTime.now()
                );
            }
            case FAILED -> {
                jobRepository.markFailed(
                        documentId,
                        contentSha256,
                        toFailureStage(fromState),
                        defaultErrorMessage(command)
                );
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
                documentIndexingSpi.markFailed(documentId, defaultErrorMessage(command));
            }
            case SKIPPED -> {
                jobRepository.markSkipped(documentId, contentSha256, defaultSkipReason(command));
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case NEW -> {
                // no-op
            }
        }
    }

    private RagIndexStage toFailureStage(IndexWorkflowState state) {
        return switch (state) {
            case DISPATCHING -> RagIndexStage.DISPATCHING;
            case PREPARING -> RagIndexStage.PREPARING;
            case CHUNKING -> RagIndexStage.CHUNKING;
            case SAVE_CHUNKS -> RagIndexStage.SAVE_CHUNKS;
            case VECTOR_INDEXING -> RagIndexStage.VECTOR_INDEXING;
            case COMMIT_INDEX -> RagIndexStage.COMMIT_INDEX;
            case COMPLETED -> RagIndexStage.COMPLETED;
            case SKIPPED -> RagIndexStage.SKIPPED;
            case NEW, QUEUED, FAILED -> RagIndexStage.QUEUED;
        };
    }

    private String defaultErrorMessage(IndexWorkflowCommand command) {
        if (command.errorMessage() != null && !command.errorMessage().isBlank()) {
            return command.errorMessage();
        }
        if (command.failureReason() != null && !command.failureReason().isBlank()) {
            return "索引失败 [" + command.failureReason() + "]";
        }
        return "索引失败";
    }

    private String defaultSkipReason(IndexWorkflowCommand command) {
        if (command.note() != null && !command.note().isBlank()) {
            return command.note();
        }
        if (command.errorMessage() != null && !command.errorMessage().isBlank()) {
            return command.errorMessage();
        }
        return "索引任务被跳过";
    }
}
